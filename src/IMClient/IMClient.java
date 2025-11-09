package IMClient;
/*
IMClient.java - Instant Message client using UDP and TCP communication.

Text-based communication of commands.
*/

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import tcp_client_server.TCPServerThread;

public class IMClient {
	// Protocol and system constants
	public static String serverAddress = "localhost";
	public static int TCPServerPort = 1234; // connection to server
	private boolean threadsInitialized = false;
	/*
	 * This value will need to be unique for each client you are running
	 */
	public static int TCPMessagePort = 1248; // port for connection between 2 clients
	public static int UDPServerPort = 1235;
	public static String onlineStatus = "100 ONLINE";
	public static String offlineStatus = "101 OFFLINE";

	private final Map<String, BuddyStatusRecord> buddyList = new ConcurrentHashMap<>();
	//visible main thread
	private volatile Socket pendingConnection = null;
	
	private BufferedReader reader; // Used for reading from standard input

	// Client state variables
	private String userId;
	private String status;


	public static void main(String[] argv) throws Exception {
		IMClient client = new IMClient();
		client.execute();
	}

	public IMClient() {
		// Initialize variables
		userId = null;
		status = null;
	}

	public void execute() {
		//not yet 
		//initializeThreads();

		String choice;
		reader = new BufferedReader(new InputStreamReader(System.in));

		printMenu();
		choice = getLine().toUpperCase();
		
		choice = choice.trim();
		while (!choice.equals("X")) {
			if (choice.equals("Y")) { // Must have accepted an incoming connection
				acceptConnection();
			} else if (choice.equals("N")) { // Must have rejected an incoming connection
				rejectConnection();
			} else if (choice.equals("R")) // Register
			{
				registerUser();
			} else if (choice.equals("L")) // Login as user id
			{
				loginUser();
			} else if (choice.equals("A")) // Add buddy
			{
				addBuddy();
			} else if (choice.equals("D")) // Delete buddy
			{
				deleteBuddy();
			} else if (choice.equals("S")) // Buddy list status
			{
				buddyStatus();
			} else if (choice.equals("M")) // Start messaging with a buddy
			{
				buddyMessage();
			} else
				System.out.println("Invalid input!");

			printMenu();
			choice = getLine().toUpperCase();
		}
		shutdown();
	}

	private void initializeThreads() {
		if(threadsInitialized){
			return ;
		}
		/* --- UDP sender Thread --- */
		Thread udpSenderThread = new Thread(new UdpSenderThread(this));
		udpSenderThread.setDaemon(true);
		udpSenderThread.start();
		//System.out.println("Backup threads started for user");

		/* --- UDP receive buddy list thread --- */
		// Thread udpReceiveThread = new Thread(new UdpReceiveThread(this));
		// udpReceiveThread.setDaemon(true);
		// udpReceiveThread.start();

		/* --- TCP accept connection thread --- */
		Thread TCPMessenger = new Thread(new TCPMessenger(this));
		TCPMessenger.setDaemon(true);
		TCPMessenger.start();
		threadsInitialized = true;
	}

	// Unkown hopst
	private void registerUser() {
        System.out.print("Enter user id to register: ");
        String newUserId = getLine();
        if (newUserId == null || newUserId.trim().isEmpty()) {
            System.out.println("User ID cannot be empty.");
            return;
        }
        
        String response = null;
        // using trywith resources ensures the socket is closed automatically
        try(Socket clientSocket = new Socket(serverAddress, TCPServerPort)) {
            // set up streams
			
            DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
            BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            //construct and send the command
            String command = "REG " + newUserId;
            outToServer.writeBytes(command + "\n");

            // read the servers response
            response = inFromServer.readLine();
            System.out.println(response);

        } catch (IOException e) {
            System.err.println("Error during registration: " + e.getMessage());
        }
        
        //process the response after the connection is closed
        if (response != null && response.startsWith("200")) {
           // System.out.println("Registration successful! You are now logged in.");
            this.userId = newUserId;
            this.status = onlineStatus;
            initializeThreads();
        }
    }

	private void loginUser() { // Login an existing user (no verification required - just set userId to input)
		System.out.print("Enter user id: ");
		this.userId = getLine();
		System.out.println("User id set to: " + this.userId);
		this.status = onlineStatus;
		initializeThreads();
	}

	private void addBuddy() { // Add buddy if have current user id
		if(!isLoggedIn()) return;
		System.out.print("Enter buddy id: ");
		String buddyId = getLine();
		if(buddyId == null || buddyId.trim().isEmpty()){
			System.out.println("Buddy Id cannot be empty.");
			return;
		}

		String response = sendTcpCommand("ADD " + this.userId + " " + buddyId, true);
		// if(response != null && response.startsWith("202")){
		// 	sendTcpCommand("REG " + buddyId, false);
		// }

	}
	private boolean isLoggedIn(){
		return this.userId != null;
	}
	private void deleteBuddy() {
		try {
			if(!isLoggedIn()) return;
			System.out.print("Enter buddy id: ");
			String newUserId = reader.readLine();
			sendTcpCommand("DEL " + this.userId + " " + newUserId, true);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void buddyStatus() { // Print out buddy status (need to store state in instance variable that
									// received from previous UDP message)
									// Budy list getBuddyList Method
		if(!isLoggedIn()){
			return ;
		}
		System.out.println("My buddy list: ");
		if(isLoggedIn()){
			if(buddyList.isEmpty()){
				System.out.println();
			}else {
				for(BuddyStatusRecord values: buddyList.values()){
					System.out.print(values.toString());
				}
			}
		}

	}

	private void buddyMessage() { // Make connection to a buddy that is online
									// Must verify that they are online and should prompt to see if they accept the
									// connection
		 if (!isLoggedIn()) return;
        System.out.print("Enter buddy id: ");
        String buddyId = getLine();
        BuddyStatusRecord buddy = buddyList.get(buddyId);

        if (buddy == null) {
            System.out.println("'" + buddyId + "' is not in your buddy list.");
            return;
        }
        if (!buddy.isOnline()) {
            System.out.println("Sorry, '" + buddyId + "' is offline.");
            return;
        }
        System.out.println("Attempting to connect...");

		String buddyIp = buddy.IP;
		int buddyPort = Integer.parseInt(buddy.buddyPort);
		try(Socket buddySocket = new Socket(buddyIp, buddyPort)){
			DataOutputStream outToBuddy = new DataOutputStream(buddySocket.getOutputStream());
			BufferedReader inFromServer = new BufferedReader(new InputStreamReader(buddySocket.getInputStream())); 


			String terminator = "q";
			String clientSentence = inFromServer.readLine();
			if(clientSentence != null && clientSentence.equalsIgnoreCase("ACCEPT")){
				System.out.println("Buddy accepted the connection");
				System.out.println("Enter your text to send to buddy. Enter q to quit.");
				new Thread(new MessageReceiverThread(buddySocket)).start();
				while (!buddySocket.isClosed()) {
					System.out.print("> ");
                    String message = getLine();
                    if (message == null || terminator.equalsIgnoreCase(message)) {
                        break;
                    }
					try{
						outToBuddy.writeBytes(message +"\n");
					}catch(SocketException e){
						//socket is closed by the receiver thread
						break;
					}
                }
			}else {
				System.out.println("Your chat request was rejected by " + buddyId + ".");
			}
			System.out.println("Buddy connection closed...");
		}catch(Exception e){
			e.printStackTrace();
		}
	}

	private void shutdown() { // Close down client and all threads
		System.out.println("Shutting down client");
		System.exit(0);
	}

	private void acceptConnection() { // User pressed 'Y' on this side to accept connection from another user
										// Send confirmation to buddy over TCP socket
										// Enter messaging mode
		if(pendingConnection == null){
			System.out.println("no pending connection to accept");
			return;
		}
		Socket buddySocket = pendingConnection;
		pendingConnection = null;//clear connection
		
		System.out.println("Attempting to connect...");
		try{
			DataOutputStream outToBuddy = new DataOutputStream(buddySocket.getOutputStream());
			outToBuddy.writeBytes("ACCEPT\n");
			System.out.println("Enter your text to send to buddy. Enter q to quit.");
			new Thread(new MessageReceiverThread(buddySocket)).start();

            // Use the main thread to send messages
            while (true) {
				System.out.print("> ");
                String message = getLine();
                if (message == null || "q".equalsIgnoreCase(message)) {
                    break;
                }
				try{
					outToBuddy.writeBytes(message + "\n");
				}catch(SocketException e){
					break;
				}
            }

			System.out.println("Buddy connection closed...");
			if(!buddySocket.isClosed()){
				buddySocket.close();
			}
			//System.out.println("Buddy connection closed.");
		}catch(IOException e){
			e.printStackTrace();
		}
	}

	private void rejectConnection() { // User pressed 'N' on this side to decline connection from another user
										// Send no message over TCP socket then close socket
	
		if (pendingConnection == null) {
            System.out.println("There is no pending connection to reject.");
            return;
        }
        System.out.println("Rejecting incoming connection...");
        try {
            DataOutputStream outToBuddy = new DataOutputStream(pendingConnection.getOutputStream());
            outToBuddy.writeBytes("REJECT\n");
            pendingConnection.close();
        } catch (IOException e) {
        } finally {
            pendingConnection = null;
        }
	}

	private String getLine() { // Read a line from standard input
		String inputLine = null;
		try {
			inputLine = reader.readLine();
		} catch (IOException e) {
			System.out.println(e);
		}
		return inputLine;
	}

	String sendTcpCommand(String command, boolean print) {
		Socket clientSocket = null;
		String response = null;
		try {
			clientSocket = new Socket(serverAddress, 1234); // Use the correct TCP port
			DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
			BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

			// Send the pre-formatted command
			outToServer.writeBytes(command + "\n");

			// Read and print the server's response
			String serverResponse = inFromServer.readLine();
			if(print) System.out.println(serverResponse);
			response = serverResponse;
		} catch (IOException e) {
			System.err.println("Error communicating with server: " + e.getMessage());
		} 
		if (clientSocket != null) {
			try {
				clientSocket.close();
				return response;
			} catch (IOException e) {
				/* ignore */ 
			}
		}
		return response;
	}

	private void printMenu() {
		System.out.println("\n\nSelect one of these options: ");
		System.out.println("  R - Register user id");
		System.out.println("  L - Login as user id");
		System.out.println("  A - Add buddy");
		System.out.println("  D - Delete buddy");
		System.out.println("  M - Message buddy");
		System.out.println("  S - Buddy status");
		System.out.println("  X - Exit application");
		System.out.print("Your choice: ");
	}
	// This class implements the TCP welcome socket for other buddies to connect to.
	// I have left it here as an example to show where the prompt to ask for
	// incoming connections could come from.
	class BuddyStatusRecord {
		public String IP;
		public String status;
		public String buddyId;
		public String buddyPort;
		public String toString() {
        	return buddyId + "\t" + status + "\t" + IP + "\t" + buddyPort;
		}

		public boolean isOnline() {
			return status.contains("100"); // Checks if the status is "100 ONLINE"
		}
	}
	class MessageReceiverThread implements Runnable {
		Socket buddySocket;
		MessageReceiverThread(Socket buddySocket){
			this.buddySocket = buddySocket;
		}
		@Override
		public void run() {
			try{
				BufferedReader  inFromBuddy = new BufferedReader(new InputStreamReader(buddySocket.getInputStream()));
				while(true){
					String message = inFromBuddy.readLine();
					if(message == null) break;
					System.out.println("\nB: "+ message);
				}
			}catch(IOException e){
			}finally {
			
				try {
					if(!buddySocket.isClosed()){
						buddySocket.close();
					}
				}catch(IOException e){

				}
			}
		}
		
	}
	class TCPMessenger implements Runnable {
		private IMClient client;
		String clientSentence;
		String terminator = "...";
		String capitalizedSentence;
		String goodByeString = "bye!\n";

		public TCPMessenger(IMClient c) {
			client = c;
		}

		public void run() {
			// This thread starts an infinite loop looking for TCP requests.
			try(ServerSocket welcomeSocket = new ServerSocket(IMClient.TCPMessagePort)){
				while(true){
					Socket connectionSocket = welcomeSocket.accept();
					if(client.pendingConnection != null){
						System.out.println("Busy with another request. Rejecting incoming call from "+connectionSocket.getInetAddress());
						DataOutputStream outputBuddy = new DataOutputStream(connectionSocket.getOutputStream());
						outputBuddy.writeBytes("REJECT");
						connectionSocket.close();
						continue;
					}
					client.pendingConnection = connectionSocket;
					System.out.print("\nDo you want to accept an incoming connection (y/n)?");
				}

			}catch(IOException e){
				e.printStackTrace();
			}
		}
	}
 
	class UdpSenderThread implements Runnable {
		private IMClient client;

		public UdpSenderThread(IMClient client) {
			this.client = client;
		}

		@Override 
		public void run() {
			try (DatagramSocket udpSocket = new DatagramSocket()) {
				udpSocket.setSoTimeout(5000); // Since the receive method call is blocking.
				InetAddress serverInetAddress = InetAddress.getByName(IMClient.serverAddress);

				while (true) {
					//System.out.println("User Id before sender thread:" + client.userId);
					if (client.userId != null) { // Use the client's actual userId field
						try {
							// Send SET command
							// System.out.println("Sending the user SET online status commands:" +
							// client.status);
							String setCommand = "SET " + client.userId + " " + client.status + " "
									+ IMClient.TCPMessagePort;
							byte[] sendBuffer = setCommand.getBytes();
							DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length,
									serverInetAddress, IMClient.UDPServerPort);
							udpSocket.send(sendPacket);

							// Send GET command
							String getCommand = "GET " + client.userId;
							sendBuffer = getCommand.getBytes();
							sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, serverInetAddress,
									IMClient.UDPServerPort);
							udpSocket.send(sendPacket);

							// Receive buddy list
							byte[] receiveBuffer = new byte[1024];
							DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
							udpSocket.receive(receivePacket);
							// System.out.println("RECEIVED FROM SERVER:" + new
							// String(receivePacket.getData()));
							String receievedString = new String(receivePacket.getData());
							Map<String, BuddyStatusRecord> newBuddyList = new ConcurrentHashMap<>();
							String[] buddyAttributes = receievedString.trim().split("\n");
							for (String line : buddyAttributes) {
								String[] parts = line.split("\\s+"); // Split by one or more spaces

								// A valid online status has 5 parts: buddyId, code, msg, ip, port
								if (parts.length >= 5) {
									BuddyStatusRecord record = new BuddyStatusRecord();
									record.buddyId = parts[0];
									record.status = parts[1] + " " + parts[2];
									record.IP = parts[3];
									record.buddyPort = parts[4];
									newBuddyList.put(record.buddyId, record);
								}
								// A valid offline status has 4 parts
								else if (parts.length >= 4) {
									BuddyStatusRecord record = new BuddyStatusRecord();
									record.buddyId = parts[0];
									record.status = parts[1] + " " + parts[2];
									record.IP = parts[3];
									record.buddyPort = "N/A";
									newBuddyList.put(record.buddyId, record);
								}
							}
							//Attach the buddy list
							client.buddyList.clear();
							client.buddyList.putAll(newBuddyList);
						} catch (SocketTimeoutException e) {
							e.printStackTrace();
						} catch (IOException e) {
							System.err.println("UDP communication error: " + e.getMessage());
						}
					}
					Thread.sleep(10000); // Wait 10 seconds
				}
			} catch (Exception e) {
				System.err.println("UdpHeartbeatThread has failed critically: " + e.getMessage());
			}
		}
	}


}
