package bft;

import sun.misc.Signal;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

class Server {

    private static final int payloadMaxSize = 512;
    private static int totalTransferred = 0;
    private static StartTime timer;
    private static int duplicates;

    private static String fileName;
    private static String directory;
    private static boolean DEBUG = false;

    private static InetAddress clientAddress;
    private static int clientPort;

    private static int port;

    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            System.out.println("ARG ERROR: No arguments. Needs to have at least 2 arguments: PORT DIRECTORY");
            help();
            return;
        } else if (args[0].equals("-h") || args[0].equals("-help")) {
            help();
            return;
        } else if (args[0].equals("-d") || args[0].equals("-debug")) {
            DEBUG = true;
        }
        port = Integer.parseInt(args[args.length - 2]);
        directory = args[args.length - 1];
        if (DEBUG) {
            System.out.println("------------------------------");
            System.out.println("Debug Info Server:");
            System.out.println("------------------------------");
            System.out.println("Server: " + InetAddress.getLocalHost());
            System.out.println("PORT : " + port);
            System.out.println("Directory : " + directory);
        }
        File dir = FileSystems.getDefault().getPath(directory).toFile().getAbsoluteFile();
        //create ftp folder if it doesn't exist
        if (!dir.exists()) {
            if (DEBUG) System.out.println("Creating " + directory + " folder...");
            new File(FileSystems.getDefault().getPath(directory).toFile().getAbsoluteFile().toString()).mkdirs();
            if (DEBUG) System.out.println(directory + " folder is created!");
        }

        System.out.println("The server is ready to receive the data...");
        setUp();
    }

    public static synchronized void setUp() throws IOException {

        DatagramSocket socket = new DatagramSocket(port);
        while (true) {
            byte[] receivedFileName = new byte[16]; // Max length 16 byte for file name
            DatagramPacket receivedFileNamePacket = new DatagramPacket(receivedFileName, receivedFileName.length);
            socket.receive(receivedFileNamePacket);
            clientAddress = receivedFileNamePacket.getAddress();
            clientPort = receivedFileNamePacket.getPort();

            // decoded Data UsingUTF82
            fileName = new String(receivedFileName, StandardCharsets.UTF_8).trim();

            //File file = FileSystems.getDefault().getPath(Path.of(directory, fileName).toString()).toFile().getAbsoluteFile();
            File file = new File(directory + fileName);

            FileOutputStream outToFile = new FileOutputStream(file);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outToFile);
            acceptTransferOfFile(bufferedOutputStream, socket);

            byte[] finalStatData = new byte[payloadMaxSize];
            DatagramPacket receivePacket = new DatagramPacket(finalStatData, finalStatData.length);

            socket.receive(receivePacket);
            printFinalStatistics(finalStatData);
            Signal.handle(new Signal("INT"), signal -> {
                socket.close();
                if (DEBUG) System.out.println("The server was stopped via Ctrl+C.");
                System.exit(0);
            });
        }
    }

    private static void acceptTransferOfFile(BufferedOutputStream outToFile, DatagramSocket socket) throws IOException {

        // last message flag
        boolean flag = false;
        int sequenceNumber = 0;
        int findLast = 0;

        while (true) {
            byte[] message = new byte[payloadMaxSize];

            // Receive packet and retrieve message
            DatagramPacket receivedPacket = new DatagramPacket(message, message.length);

            socket.setSoTimeout(0);
            socket.receive(receivedPacket);

            message = receivedPacket.getData();
            totalTransferred += receivedPacket.getLength();

            // start the timer at the point transfer begins
            if (sequenceNumber == 0) {
                timer = new StartTime();
            }

            // Get port and address for sending acknowledgment
            InetAddress address = receivedPacket.getAddress();
            int port = receivedPacket.getPort();

            // Retrieve sequence number
            sequenceNumber = ((message[0] & 0xff) << 24) +((message[1] & 0xff) << 16) +((message[2] & 0xff) << 8) + (message[3] & 0xff);

            // Retrieve the last message flag
            // a returned value of true means we have a problem
            flag = (message[4] & 0xff) == 1;

            // if sequence number is the last one +1, then it is correct
            // we get the data from the message and write the message
            // that it has been received correctly
            if (sequenceNumber == (findLast + 1)) {
                //System.out.println("SQN="+sequenceNumber);
                // set the last sequence number to be the one we just received
                findLast = sequenceNumber;

                //System.out.println("newfindLast="+findLast);
                byte[] fileByteArray = new byte[receivedPacket.getLength() - 5];
                // Retrieve data from message
                System.arraycopy(message, 5, fileByteArray, 0, receivedPacket.getLength() - 5);

                // Write the message to the file and print received message
                outToFile.write(fileByteArray, 0, receivedPacket.getLength() - 5);

                if (DEBUG) System.out.println("Received: Sequence number:" + findLast);

                // Send acknowledgement
                sendAck(findLast, socket, clientAddress, clientPort);

            } else {
                System.out.println("Expected sequence number: " + (findLast + 1) + " but received " + sequenceNumber + ". DISCARDING");
                // Re send the acknowledgement
                sendAck(findLast, socket, clientAddress, clientPort);
                duplicates++;
            }

            // Check for last message
            if (flag) {
                outToFile.close();
                break;
            }
        }
    }

    private static void sendAck(int findLast, DatagramSocket socket, InetAddress address, int port) throws IOException {
        // send acknowledgement
        byte[] ackPacket = new byte[4];
        ackPacket[0] = (byte) (findLast >> 24);
        ackPacket[1] = (byte) (findLast >> 16);
        ackPacket[2] = (byte) (findLast >> 8);
        ackPacket[3] = (byte) (findLast);
        // the datagram packet to be sent
        DatagramPacket acknowledgement = new DatagramPacket(ackPacket, ackPacket.length, address, port);
        socket.send(acknowledgement);
        if (DEBUG) System.out.println("Sent ack: Sequence Number = " + findLast);
    }

    private static void printFinalStatistics(byte[] finalStatData) {
        String decodedDataUsingUTF8 = new String(finalStatData, StandardCharsets.UTF_8);
        System.out.println("\n\nStatistics of transfer");
        System.out.println("------------------------------");
        System.out.println("File has been saved as: " + fileName);
        System.out.println("Statistics of transfer");
        System.out.println("number of duplicates: " + duplicates);
        System.out.println("" + decodedDataUsingUTF8.trim());
        System.out.println("------------------------------");
    }

    private static void help() {
        System.out.println("Usage : java bft. Server [ OPTIONS ...] PORT DIRECTORY\n" +
                "where\n" +
                "OPTIONS := { -d[ ebug ] | -h[elp] }");
    }
}
