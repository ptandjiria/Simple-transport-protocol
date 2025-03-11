import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.crypto.Data;

public class Sender {
    // Server details and operation duration
    private int sender_port;
    private int receiver_port;
    private String txt_file_to_send;
    private int max_win;
    private int rto;
    private float flp;
    private float rlp;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private SenderStates state;
    private int seqno;
    private int isn;
    private double timeFirstPacketSent;
    private final int MSS = 1000;
    private int totalBytesRead;
    private long fileLength;
    private Map<Integer, DatagramPacket> segmentBuffer; // buffer for sent data packets
    private Timer timer; // timer for tracking oldest unacked segment
    private int oldestUnackedSeqNo; // sequence number of oldest unacked segment
    private boolean synSent = false;
    private final Lock logLock = new ReentrantLock();
    private int dupACKCount;
    private int totaldupACKs;
    private int totalRetransmissions;
    private int totalOriginalSegments;
    private int totalOriginalData;
    private int totalOriginalACKed;
    private int dataSegmentsDropped = 0;
    private int ackSegmentsDropped = 0;
    private boolean firstPacket = true;

    public Sender(int sender_port, int receiver_port, String txt_file_to_send, int max_win, int rto, float flp,
            float rlp) {
        this.sender_port = sender_port;
        this.receiver_port = receiver_port;
        this.txt_file_to_send = txt_file_to_send;
        this.max_win = max_win;
        this.rto = rto;
        this.flp = flp;
        this.rlp = rlp;
        this.isRunning = true;
        this.state = SenderStates.CLOSED;
        Random random = new Random();
        this.seqno = random.nextInt(1 << 16); // Generates a random number for ISN
        this.isn = seqno;
        this.totalBytesRead = 0;
        this.fileLength = 0;
        this.segmentBuffer = new HashMap<>();
        this.oldestUnackedSeqNo = isn;
        this.dupACKCount = 0;
        this.totaldupACKs = 0;
        this.totalRetransmissions = 0;
        this.totalOriginalSegments = 0;
        this.totalOriginalData = 0;
        this.totalOriginalACKed = 0;
        String logFile = "sender_log.txt";
        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write("");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static public enum SenderStates {
        CLOSED, SYN_SENT, EST, CLOSING, FIN_WAIT;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(sender_port); // Creates socket
        socket.connect(new InetSocketAddress("127.0.0.1", receiver_port));

        new ReceiveThread().start();

        sendSegment(); // Begins sending packets to the receiver
    }

    public byte[] readBytesFromFile(FileInputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        FileChannel channel = inputStream.getChannel();
        channel.position(totalBytesRead);

        byte[] buffer = new byte[Math.min(MSS, inputStream.available())];
        int bytesRead;

        bytesRead = inputStream.read(buffer, 0, Math.min(MSS, inputStream.available()));
        outputStream.write(buffer, 0, bytesRead);
        this.totalBytesRead += bytesRead;
        return outputStream.toByteArray();
    }

    // Main method for sending SYN segment to the receiver
    private void sendSegment() {
        state = SenderStates.SYN_SENT;
        while (isRunning) {
            String messageType = null;
            DatagramPacket packet = null;
            ByteBuffer header = null;
            if (state == SenderStates.SYN_SENT && !synSent) {
                // create STP header
                int type = 2;
                header = ByteBuffer.allocate(4);
                header.order(ByteOrder.BIG_ENDIAN);
                messageType = "SYN";

                header.putShort((short) (type & 0xFFFF));
                header.putShort((short) (seqno & 0xFFFF));

                byte[] data = header.array();

                // the datagram packet will only consist of the headers since it's a SYN segment
                packet = new DatagramPacket(data, data.length);
                segmentBuffer.put(seqno, packet);
                synSent = true;
            } else if (state == SenderStates.EST) {
                if (segmentBuffer.isEmpty()) {
                    oldestUnackedSeqNo = seqno;
                }
                if (segmentBuffer.size() < max_win / 1000) {
                    totalOriginalSegments++;
                    // create STP header
                    messageType = "DATA";
                    int type = 0;

                    try (FileInputStream fileInputStream = new FileInputStream(txt_file_to_send)) {
                        byte[] data = readBytesFromFile(fileInputStream);
                        header = ByteBuffer.allocate(4 + data.length);
                        header.order(ByteOrder.BIG_ENDIAN);
                        header.putShort((short) (type & 0xFFFF));
                        header.putShort((short) (seqno & 0xFFFF));
                        header.put(data);
                        File file = new File(txt_file_to_send);
                        this.fileLength = file.length();
                        if (this.totalBytesRead >= file.length()) {
                            state = SenderStates.CLOSING;
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    byte[] data = header.array();

                    packet = new DatagramPacket(data, data.length);

                    // add packet to segment buffer in case of retransmission
                    segmentBuffer.put(seqno, packet);
                } else {
                    continue;
                }
            } else if (state == SenderStates.CLOSING) {
                if (segmentBuffer.isEmpty()) {
                    // create STP header
                    header = ByteBuffer.allocate(4);
                    header.order(ByteOrder.BIG_ENDIAN);
                    messageType = "FIN";
                    int type = 3;
                    state = SenderStates.FIN_WAIT;

                    header.putShort((short) (type & 0xFFFF));
                    header.putShort((short) (seqno & 0xFFFF));
                    byte[] data = header.array();

                    packet = new DatagramPacket(data, data.length);
                    segmentBuffer.put(seqno, packet);
                } else {
                    continue;
                }
            } else {
                continue;
            }

            // record the time the first packet is sent
            if (timeFirstPacketSent == 0) {
                timeFirstPacketSent = System.currentTimeMillis();
            }

            // Starts the timer for the oldest unacked segment
            if (seqno == oldestUnackedSeqNo) {
                startTimer();
            }

            // drop packet if probability is less than flp and skip to next iteration
            Random probability = new Random();
            float chance = probability.nextFloat();
            if (0 < chance && chance <= flp) {
                logLock.lock();
                // log message in file
                try {
                    double timestamp = System.currentTimeMillis() - timeFirstPacketSent;
                    String logMessage = "drp " + timestamp + " " + messageType + " " + seqno + " "
                            + (header.position() - 4) + "\n";
                    appendToLog(logMessage);

                    // Increments the seqno if in EST state
                    if (state == SenderStates.EST || state == SenderStates.CLOSING) {
                        seqno = (seqno + (header.position() - 4)) % 65535;
                        dataSegmentsDropped++;
                    }

                    totalOriginalData += (header.position() - 4);
                } finally {
                    logLock.unlock();
                }
                continue;
            } else {
                try {
                    logLock.lock();
                    // log message in file
                    try {
                        double timestamp = System.currentTimeMillis() - timeFirstPacketSent;
                        if (firstPacket) {
                            timestamp = 0.0;
                        }
                        String logMessage = "snd " + timestamp + " " + messageType + " " + seqno + " "
                                + (header.position() - 4) + "\n";
                        appendToLog(logMessage);
                    } finally {
                        logLock.unlock();
                    }

                    // Increments the seqno if in EST state
                    if (state == SenderStates.EST || state == SenderStates.CLOSING) {
                        seqno = (seqno + (header.position() - 4)) % 65535;
                    }

                    socket.send(packet); // Sends the packet
                    totalOriginalData += (header.position() - 4);
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
            firstPacket = false;
        }
        socket.close(); // Closes the socket once finished
    }

    private void startTimer() {
        if (timer != null) {
            timer.cancel();
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Handle timeout event
                handleTimeout();
                dupACKCount = 0;
            }
        }, rto);
    }

    private void handleTimeout() {
        // Resend the oldest unacknowledged segment
        if (segmentBuffer.containsKey(oldestUnackedSeqNo)) {
            DatagramPacket packet = segmentBuffer.get(oldestUnackedSeqNo);
            try {
                logLock.lock();
                String messageType = null;
                // log message in file
                ByteBuffer wrapped = ByteBuffer.wrap(packet.getData(), 0, packet.getLength()); // Processes the response
                
                wrapped.order(ByteOrder.BIG_ENDIAN);
                int type = wrapped.getShort() & 0xFFFF;
                if (type == 2) {
                    messageType = "SYN";
                    state = SenderStates.SYN_SENT;
                } else if (type == 0) {
                    messageType = "DATA";
                    totalRetransmissions++;
                } else if (type == 3) {
                    messageType = "FIN";
                }
                try {
                    double timestamp = System.currentTimeMillis() - timeFirstPacketSent;
                    String logMessage = "snd " + timestamp + " " + messageType + " " + oldestUnackedSeqNo + " "
                            + (packet.getLength() - 4) + "\n";
                    appendToLog(logMessage);
                } finally {
                    logLock.unlock();
                }

                socket.send(packet); // Resend the packet
                startTimer(); // Restart the timer
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Thread for handling received responses from the receiver
    class ReceiveThread extends Thread {
        public void run() {
            byte[] buffer = new byte[4]; // Buffer to store the received data
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (isRunning) {
                try {
                    socket.receive(packet); // Waits for a response
                    double timestamp = System.currentTimeMillis() - timeFirstPacketSent;
                    ByteBuffer wrapped = ByteBuffer.wrap(packet.getData(), 0, packet.getLength()); // Processes the response
                    
                    wrapped.order(ByteOrder.BIG_ENDIAN);
                    int type = wrapped.getShort() & 0xFFFF;
                    if (type == 1) {
                        processAckSegment(wrapped, timestamp);
                    }
                } catch (IOException e) {
                    if (!isRunning) {
                        break; // Exits if the sender is no longer running
                    }
                }
            }
        }
    }

    private void processAckSegment(ByteBuffer buffer, double timestamp) {

        int ackNo = buffer.getShort() & 0xFFFF;

        // Drop ack if probability is less than rlp
        Random probability = new Random();
        float chance = probability.nextFloat();
        if (0 < chance && chance <= rlp) {
            // log message in file
            logLock.lock();
            try {
                String logMessage = "drp " + timestamp + " ACK " + ackNo + " 0\n";
                appendToLog(logMessage);
                if (state == SenderStates.EST && ackNo != isn) {
                    ackSegmentsDropped++;
                }
            } finally {
                logLock.unlock();
            }
            return;
        }

        // log message in file
        logLock.lock();
        try {
            String logMessage = "rcv " + timestamp + " ACK " + ackNo + " 0\n";
            appendToLog(logMessage);
        } finally {
            logLock.unlock();
        }

        if ((ackNo > oldestUnackedSeqNo && ackNo <= oldestUnackedSeqNo + max_win)
                || (ackNo < oldestUnackedSeqNo && ackNo <= (oldestUnackedSeqNo + max_win) % 65535)) {
            if (state == SenderStates.FIN_WAIT) {
                state = SenderStates.CLOSED;
                isRunning = false;
                timer.cancel();
            }
            if (state == SenderStates.SYN_SENT) {
                segmentBuffer.remove(oldestUnackedSeqNo);
                state = SenderStates.EST;
                seqno = ackNo;
                oldestUnackedSeqNo = ackNo;
            }
            if (state == SenderStates.EST || state == SenderStates.CLOSING) {
                if (ackNo < oldestUnackedSeqNo) {
                    totalOriginalACKed += (ackNo - 0 + (65535 - oldestUnackedSeqNo));
                } else if (ackNo >= oldestUnackedSeqNo) {
                    totalOriginalACKed += (ackNo - oldestUnackedSeqNo);
                }
                // remove acked segments from the buffer
                segmentBuffer.remove(oldestUnackedSeqNo);
                if (state == SenderStates.CLOSING) {
                    segmentBuffer.clear();
                }

                // slide the window by updating oldestUnackedSeqNo
                oldestUnackedSeqNo = ackNo;

                // start timer for the new oldest unacked segment
                if (!segmentBuffer.isEmpty()) {
                    startTimer();
                }
            }
        } else if (ackNo <= oldestUnackedSeqNo && ackNo != isn) {
            // if the ack acknowledges previously acked segments
            dupACKCount++;
            if (state == SenderStates.EST || state == SenderStates.CLOSING) {
                totaldupACKs++;
            }
            if (dupACKCount == 3) {
                handleTimeout(); // retransmit oldest unacked segment
                dupACKCount = 0;
            }
        }

        if (state == SenderStates.CLOSED) {
            // write summary in log
            String logFile = "sender_log.txt";
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write("Original data sent: " + totalOriginalData + "\n");
                writer.write("Original data acked: " + totalOriginalACKed + "\n");
                writer.write("Original segments sent: " + totalOriginalSegments + "\n");
                writer.write("Retransmitted segments: " + totalRetransmissions + "\n");
                writer.write("Dup acks received: " + totaldupACKs + "\n");
                writer.write("Data segments dropped: " + dataSegmentsDropped + "\n");
                writer.write("Ack segments dropped: " + ackSegmentsDropped + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.exit(0);
        }
    }

    private void appendToLog(String message) {
        String logFile = "sender_log.txt";
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Ensures the correct number of arguments (7) are provided
        if (args.length != 7) {
            System.err.println("Usage: java Sender <sender_port> <receiver_port> <txt_file_to_send>"
                    + "<max_win> <rto> <flp> <rlp>");
            System.exit(1);
        }

        // Initializes and starts the sender with the provided arguments
        Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2],
                Integer.parseInt(args[3]), Integer.parseInt(args[4]), Float.parseFloat(args[5]),
                Float.parseFloat(args[6]));
        try {
            sender.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
