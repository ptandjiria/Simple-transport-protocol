# Simple-transport-protocol
Developed a Simple Transport Protocol (STP) over UDP to ensure reliable data transfer between hosts despite packet loss. Implemented a sender-receiver architecture using sliding window protocol and automatic retransmission for lost packets. Key features:

- Sender: Reads and transmits file data in segments, manages acknowledgments, and handles retransmissions using a timeout mechanism.

- Receiver: Processes incoming packets, buffers out-of-order data, and ensures in-order file reconstruction.
  
- Designed multi-threaded architecture with state management for connection setup/teardown, packet tracking, and retransmission handling.
  
- Utilised hash maps for efficient packet management and sliding window logic for flow control.
