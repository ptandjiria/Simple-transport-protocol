import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.util.*;
import java.io.File;

public class Receiver {
    private int receiver_port;
    private int sender_port;
    private String txt_file_received;
    private int max_win;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private int ack;
    private final int ACK_TYPE = 1;
    private long timeFirstSYNreceived = 0;
    private ReceiverStates state = ReceiverStates.CLOSED;
    private Map<Integer, byte[]> receiveBuffer; // map to store received packets
    private int totalOriginalData;
    private int totalOriginalSegments;
    private int totalDupSegments;
    private int totalDupACKS;
    private boolean firstWrite = false;

    // Constructor to initialize receiver with port and wait time
    public Receiver(int receiver_port, int sender_port, String textFilename, int max_win) {
        this.receiver_port = receiver_port;
        this.sender_port = sender_port;
        this.txt_file_received = textFilename;
        this.max_win = max_win;
        this.isRunning = true;
        this.ack = -1;
        this.timeFirstSYNreceived = 0;
        this.state = ReceiverStates.LISTEN;
        this.receiveBuffer = new HashMap<>();
        this.totalOriginalData = 0;
        this.totalOriginalSegments = 0;
        this.totalDupSegments = 0;
        this.totalDupACKS = 0;
    }

    static public enum ReceiverStates {
        CLOSED, LISTEN, EST, TIME_WAIT;
    }

    // Starts the receiver to listen for messages
    public void start() throws IOException {
        socket = new DatagramSocket(receiver_port);
        receiveAndRespond(); // Main operation of receiver
    }

    public int writeBytesToFile(byte[] bytes) throws IOException {
        int byteCount = 0;
        boolean append = false;
        if (firstWrite) {
            append = true;
        }
        try (FileOutputStream fos = new FileOutputStream(txt_file_received, append)) {
            int i = 0;
            while (i < bytes.length && bytes[i] != 0) {
                fos.write(bytes[i]);
                i++;
            }
            byteCount = i;
        } catch (IOException e) {
            e.printStackTrace();
        }
        totalOriginalData += byteCount;
        return byteCount;
    }

    public int calculateByteCount(byte[] bytes) {
        int byteCount = 0;
        int i = 0;
        while (i < bytes.length && bytes[i] != 0) {
            i++;
        }
        byteCount = i;
        return byteCount;
    }

    // Receives messages and sends back responses indicating odd or even
    private void receiveAndRespond() {
        byte[] buffer = new byte[1004]; // Buffer to hold incoming bytes
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (isRunning) {
            try {
                socket.receive(packet); // Receive incoming packet

                // Check sender's port
                if (packet.getPort() != sender_port) {
                    continue;
                }

                ByteBuffer wrapped = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                wrapped.order(ByteOrder.BIG_ENDIAN);
                int type = wrapped.getShort() & 0xFFFF; // Extract the packet type
                int seqno = wrapped.getShort() & 0xFFFF;
                int dataCount = 0;

                if (state == ReceiverStates.LISTEN) {
                    timeFirstSYNreceived = System.currentTimeMillis();
                    ack = seqno;
                }

                byte[] data = new byte[wrapped.remaining()];
                wrapped.get(data); // read bytes into byte array

                // Determine type
                String messageType = null;
                if (type == 2) {
                    messageType = "SYN";
                } else if (type == 0) {
                    messageType = "DATA";
                } else if (type == 3) {
                    messageType = "FIN";
                }
                // Log received receipt
                double timestamp = System.currentTimeMillis() - timeFirstSYNreceived;
                String logFile = "receiver_log.txt";
                //File file = new File(logFile);
                if (state == ReceiverStates.LISTEN) {
                    try (FileWriter writer = new FileWriter(logFile, false)) {
                        writer.write("rcv " + timestamp + " " + messageType + " " + seqno + " "
                                + calculateByteCount(data) + "\n");
                    }
                } else {
                    try (FileWriter writer = new FileWriter(logFile, true)) {
                        writer.write("rcv " + timestamp + " " + messageType + " " + seqno + " "
                                + calculateByteCount(data) + "\n");
                    }
                }

                // check if packet is within receive window
                if ((seqno - ack) % 65535 >= 0 && (seqno - ack) % 65535 < max_win) {

                    // determine if packet is in order
                    if (seqno == ack) {
                        if (type == 2) {
                            state = ReceiverStates.EST;
                            ack = seqno + 1;
                        } else if (type == 0) {
                            totalOriginalSegments++;
                            // write to file
                            dataCount = writeBytesToFile(data);
                            firstWrite = true;
                            ack = seqno + dataCount;
                            processBufferedPackets(packet);
                        } else if (type == 3) {
                            state = ReceiverStates.TIME_WAIT;
                            new TimerThread().start();
                            ack = seqno + 1;
                        }

                        // check if the ack no. is now over 2^16 -1
                        if (ack > 65535) {
                            ack = (ack - 65535);
                        }
                    } else {
                        // packet is out of order so buffer it
                        receiveBuffer.put(seqno, data);

                        // allow receiver to send duplicate ack
                        if (type == 0) {
                            totalDupACKS++;
                        }
                    }
                } else {
                    if (type == 0) {
                        // duplicate data segment was received
                        totalDupSegments++;

                        // allow receiver to send duplicate ack
                        totalDupACKS++;
                    }
                }

                ByteBuffer header = ByteBuffer.allocate(4);
                header.order(ByteOrder.BIG_ENDIAN);
                header.putShort((short) (ACK_TYPE & 0xFFFF));
                header.putShort((short) (ack & 0xFFFF));

                byte[] responseBuffer = header.array();

                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length,
                        packet.getAddress(), packet.getPort());

                socket.send(responsePacket); // Send response

                // log sent packet
                timestamp = System.currentTimeMillis() - timeFirstSYNreceived;
                //String logFile = "receiver_log.txt";
                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.write("snd " + timestamp + " ACK" + " " + ack + " " + "0\n");
                }
            } catch (IOException e) {
                // Handle I/O exceptions
                e.printStackTrace();
                break;
            }
        }

        socket.close(); // Close the socket when done
    }

    private void processBufferedPackets(DatagramPacket packet) throws IOException {
        if (!receiveBuffer.isEmpty()) {
            while (receiveBuffer.containsKey(ack)) {
                byte[] data = receiveBuffer.get(ack);
                int dataCount = writeBytesToFile(data);
                receiveBuffer.remove(ack);
                ack = ack + dataCount;
            }
        }
    }

    class TimerThread extends Thread {
        public void run() {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            isRunning = false;
            state = ReceiverStates.CLOSED;
            String logFile = "receiver_log.txt";
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write("Original data received: " + totalOriginalData + "\n");
                writer.write("Original segments received: " + totalOriginalSegments + "\n");
                writer.write("Dup data segments received: " + totalDupSegments + "\n");
                writer.write("Dup ack segments sent: " + totalDupACKS + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.exit(0);
        }
    }

    public static void main(String[] args) {
        // Check if the correct number of arguments (4) are provided
        if (args.length != 4) {
            System.err.println("Usage: java Receiver <receiver_port> <sender_port> <txt_file_received> <max_win>");
            System.exit(1);
        }

        int receiverPort = Integer.parseInt(args[0]);
        int senderPort = Integer.parseInt(args[1]);
        String textFilename = args[2];
        int maxWin = Integer.parseInt(args[3]);

        Receiver receiver = new Receiver(receiverPort, senderPort, textFilename, maxWin);
        try {
            receiver.start(); // Start the receiver
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
