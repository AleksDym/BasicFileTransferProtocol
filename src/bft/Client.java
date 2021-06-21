package bft;

import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.Random;

public class Client {

    private static boolean DEBUG = false;

    private static int port;
    private static String hostName;
    private static InetAddress serverAddress;

    private static long fileSize;
    private static String fileName;
    private static String filePath;

    private static StartTime timer = null;
    private static int retransmitted = 0;
    private static int retransTimeout = 2000;
    private static int payloadMaxSize = 512;
    private static int totalTransferred = 0;

    public static void main(String[] args) throws IOException {

        if (args.length < 3) {
            System.out.println("ARG ERROR: No arguments. Needs to have at least 3 arguments: SERVER PORT FILE");
            help();
            return;
        } else if (args[0].equals("-h") || args[0].equals("-help")) {
            help();
            return;
        } else if (args[0].equals("-d") || args[0].equals("-debug")) {  // Debug arg handle
            DEBUG = true;

        } else if (args[0].equals("-r")) {                              // retrans_timeout_ms handle
            if (args[1].matches("(\\d+)")) {
                retransTimeout = Integer.parseInt(args[1]);
            } else {
                System.out.println("ARG ERROR: retrans_timeout_ms argument needs to be a number");
                help();
                return;
            }
        }

        filePath = args[args.length - 1];
        fileName = Path.of(filePath).getFileName().toString();
        port = Integer.parseInt(args[args.length - 2]);
        hostName = args[args.length - 3];
        InetAddress clientAddress = null;


        try {
            clientAddress = InetAddress.getLocalHost();
            serverAddress = InetAddress.getByName(hostName);
        } catch (UnknownHostException e) {
            System.out.println("ERROR: unknown host!");
        }

        if (DEBUG) {
            System.out.println("------------------------------");
            System.out.println("Debug Info Client:");
            System.out.println("------------------------------");
            System.out.println("Client Address: " + clientAddress
                    + "\nServer Address: " + serverAddress
                    + "\nFile name: " + fileName
            );
        }
        setUp();
    }

    public static void setUp() throws IOException {

        if (DEBUG) System.out.println("Ready to send the file " + fileName + ".");
        DatagramSocket socket = new DatagramSocket();

        byte[] saveFileAsData;
        if (fileName.getBytes().length <= 16)
            saveFileAsData = fileName.getBytes();
        else {
            if (DEBUG) System.out.println("the file name is too long.");
            return;
        }

        File file = FileSystems.getDefault().getPath(filePath).toFile().getAbsoluteFile();

        if (!file.exists()) {
            if (DEBUG) System.out.println("The file doesn't exists.");
            return;
        } else if (file.isDirectory()) {
            if (DEBUG) System.out.println("The path is a directory.");
            return;
        }

        // Get length of file in bytes
        fileSize = file.length();
        if (fileSize < 4294967296L) {
            DatagramPacket fileStatPacket = new DatagramPacket(saveFileAsData, saveFileAsData.length, serverAddress, port);
            socket.send(fileStatPacket);

            try (FileInputStream fileInputStream = new FileInputStream(file);) {
                timer = new StartTime();
                beginTransfer(socket, fileInputStream, serverAddress);

            } catch (IOException e) {
                System.out.println(e.getMessage());
            }

            String finalStatString = getFinalStatistics();
            sendServerFinalStatistics(socket, serverAddress, finalStatString);

        } else {
            if (DEBUG) System.out.println("The file is too big.");
        }
        socket.close();
    }

    private static void sendServerFinalStatistics(DatagramSocket socket, InetAddress address, String finalStatString) {
        byte[] bytesData;
        // convert string to bytes so we can send
        bytesData = finalStatString.getBytes();
        DatagramPacket statPacket = new DatagramPacket(bytesData, bytesData.length, address, port);
        try {
            socket.send(statPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void beginTransfer(DatagramSocket socket, FileInputStream fileInputStream, InetAddress address) throws IOException {

        int sequenceNumber = 0;
        boolean flag;
        int ackSequence = 0;

        for (long i = 0; i < fileSize; i = i + payloadMaxSize - 5) {
            sequenceNumber += 1;
            // Create message
            int messageSize = (int) Math.min(payloadMaxSize, fileSize - i + 5);
            byte[] message = new byte[messageSize];
            message[0] = (byte) (sequenceNumber >> 24);
            message[1] = (byte) (sequenceNumber >> 16);
            message[2] = (byte) (sequenceNumber >> 8);
            message[3] = (byte) (sequenceNumber);

            if ((i + payloadMaxSize - 5) >= fileSize) {
                flag = true;
                message[4] = (byte) (1);
            } else {
                flag = false;
                message[4] = (byte) (0);
            }

            try {
                if (!flag) {
                    // the payload 5 begins
                    fileInputStream.read(message, 5, payloadMaxSize - 5);
                } else { // If it is the last packet
                    fileInputStream.read(message, 5, (int) (fileSize - i));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            DatagramPacket sendPacket = new DatagramPacket(message, message.length, address, port);

            socket.send(sendPacket);

            totalTransferred += sendPacket.getLength();

            if (DEBUG) System.out.println("Sent: Sequence number = " + sequenceNumber);

            // For verifying the the packet
            boolean ackRec;

            // The acknowledgment is not correct
            while (true) {
                // Create another packet by setting a byte array and creating
                // data gram packet
                byte[] ack = new byte[4];
                DatagramPacket ackpack = new DatagramPacket(ack, ack.length);

                try {
                    // set the socket timeout for the packet acknowledgment
                    socket.setSoTimeout(retransTimeout);
                    socket.receive(ackpack);
                    ackSequence = ((ack[0] & 0xff) << 24) + ((ack[1] & 0xff) << 16) + ((ack[2] & 0xff) << 8) + (ack[3] & 0xff);
                    ackRec = true;

                }
                // we did not receive an ack
                catch (SocketTimeoutException e) {
                    if (DEBUG) System.out.println("Socket timed out waiting for the " + sequenceNumber);
                    ackRec = false;
                }

                // everything is ok so we can move on to next packet
                // Break if there is an acknowledgment next packet can be sent
                if ((ackSequence == sequenceNumber) && (ackRec)) {
                    if (DEBUG) System.out.println("Ack received: Sequence Number = " + ackSequence);
                    break;
                }
                // Re send the packet
                else {
                    socket.send(sendPacket);
                    if (DEBUG) System.out.println("Resending: Sequence Number = " + sequenceNumber);
                    // Increment retransmission counter
                    retransmitted += 1;
                }
            }
        }
    }

    private static String getFinalStatistics() {

        double transferTime = timer.getTimeElapsed() / 1000;
        double throughput = fileSize / 1024.0 / 1024.0 / transferTime;
        BigDecimal fileSizeKB = BigDecimal.valueOf(fileSize / 1024.0);
        BigDecimal fileSizeMB = BigDecimal.valueOf(fileSize / 1024.0 / 1024.0);

        DecimalFormat df = new DecimalFormat("####.####");
        System.out.println("Statistics of transfer");
        System.out.println("------------------------------");
        System.out.println("File " + fileName + " has been sent successfully.");
        System.out.println("The size of the File was " + df.format(fileSizeKB) + " KB");
        System.out.println("This is approx: " + df.format(fileSizeMB) + " MB");
        System.out.println("Time for transfer was " + transferTime + " Seconds");
        System.out.printf("Throughput was %.2f MB Per Second\n", throughput);
        System.out.println("Number of retransmissions: " + retransmitted);
        System.out.println("------------------------------");

        return "File Size: " + df.format(fileSizeMB) + " MB\n"
                + "Throughput: " + df.format(throughput) + " Mbps\n"
                + "Total transfer time: " + transferTime + " Seconds";
    }

    private static void help() {
        System.out.println("Usage : java bft. Client [ OPTIONS ...] SERVER PORT FILE\n" +
                "where\n" +
                "OPTIONS := { -d[ ebug ] | -h[elp] | -r retrans_timeout_ms }");
    }
}
