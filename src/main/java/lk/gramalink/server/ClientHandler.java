package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.TransportCodec;
import lk.gramalink.shared.TransportFrame;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/** Owns one mixed-frame connection; application handlers inject the backend command dispatcher. */
public final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final SessionManager sessionManager;
    private final FileTransferService fileTransferService;
    private final Runnable onDisconnect;
    private final MessageDispatcher dispatcher;

    public ClientHandler(Socket clientSocket) {
        this(clientSocket, new SessionManager());
    }

    public ClientHandler(Socket clientSocket, SessionManager sessionManager) {
        this(clientSocket, sessionManager, FileTransferService.unavailable());
    }

    public ClientHandler(Socket clientSocket, SessionManager sessionManager, FileTransferService fileTransferService) {
        this(clientSocket, sessionManager, fileTransferService, () -> { });
    }

    public ClientHandler(Socket socket, SessionManager sessions, FileTransferService files, MessageDispatcher dispatcher) {
        this(socket, sessions, files, () -> { }, dispatcher);
    }

    ClientHandler(Socket clientSocket, Runnable onDisconnect) {
        this(clientSocket, new SessionManager(), onDisconnect);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, Runnable onDisconnect) {
        this(clientSocket, sessionManager, FileTransferService.unavailable(), onDisconnect);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, FileTransferService fileTransferService,
                  Runnable onDisconnect) {
        this(clientSocket, sessionManager, fileTransferService, onDisconnect, null);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, FileTransferService fileTransferService,
                  Runnable onDisconnect, MessageDispatcher dispatcher) {
        this.clientSocket = Objects.requireNonNull(clientSocket, "Client socket is required.");
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        this.fileTransferService = Objects.requireNonNull(fileTransferService, "File service is required.");
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "Disconnect callback is required.");
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {
        String address = clientSocket.getRemoteSocketAddress().toString();
        System.out.println("Client connected: " + address);
        try (var transfers = new FileTransferTransport(clientSocket, sessionManager, fileTransferService)) {
            var input = new DataInputStream(clientSocket.getInputStream());
            // Replies and routed events use this same manager-owned writer and lock.
            var writer = sessionManager.writerFor(clientSocket);
            TransportFrame frame;
            while ((frame = TransportCodec.readFrame(input)) != null) {
                Message response;
                if (frame instanceof FileChunk chunk) {
                    System.out.println("Message received from " + address + ": FILE_CHUNK");
                    response = transfers.receiveChunk(chunk);
                } else {
                    Message message = ((TransportFrame.JsonFrame) frame).message();
                    System.out.println("Message received from " + address + ": " + message.type());
                    if (message.type() == MessageType.FILE_META) {
                        response = transfers.negotiate(message);
                    } else if (message.type() == MessageType.HEARTBEAT && sessionManager.recordHeartbeat(clientSocket)) {
                        var payload = JsonNodeFactory.instance.objectNode().put("acknowledgedType", "HEARTBEAT");
                        response = new Message(MessageType.ACK, message.correlationId(), null, payload);
                    } else if (dispatcher != null) {
                        response = dispatcher.handle(clientSocket, message);
                    } else {
                        var payload = JsonNodeFactory.instance.objectNode()
                                .put("code", "NOT_IMPLEMENTED")
                                .put("message", message.type() == MessageType.HEARTBEAT
                                        ? "Heartbeat requires an authenticated session; authentication is not available yet."
                                        : "Operation " + message.type() + " is not implemented.");
                        response = new Message(MessageType.ERROR, message.correlationId(), null, payload);
                    }
                }
                try {
                    // Preflight before writing: a large correlation ID may fill the reply.
                    JsonUtil.toJson(response);
                } catch (IOException exception) {
                    response = new Message(response.type(), response.payload());
                }
                writer.send(response);
                if (frame instanceof FileChunk chunk && chunk.last() && response.type() == MessageType.ACK) {
                    var session = sessionManager.findSession(clientSocket).orElse(null);
                    if (session != null) {
                        var complete = fileTransferService.completion(session, chunk.transferId());
                        if (complete.isPresent()) writer.send(complete.get());
                    }
                }
                if (response.type() == MessageType.FILE_META && response.payload().path("status").asText().equals("READY")
                        && response.payload().path("direction").asText().equals("DOWNLOAD")) {
                    var session = sessionManager.findSession(clientSocket).orElse(null);
                    var grant = new FileTransferService.Grant(java.util.UUID.fromString(response.payload().path("transferId").asText()),
                            FileTransferService.Direction.DOWNLOAD, response.payload().path("totalBytes").asLong());
                    Thread.startVirtualThread(() -> {
                        try (var download = fileTransferService.download(session, grant)) {
                            long sequence = 0, remaining = grant.totalBytes();
                            do {
                                byte[] data = download.readNBytes((int)Math.min(65536, remaining));
                                if (data.length == 0 && remaining > 0) throw new IOException("Incomplete stored file.");
                                remaining -= data.length;
                                transfers.sendChunk(new FileChunk(grant.transferId(), sequence++, remaining == 0, data));
                            } while (remaining > 0);
                        } catch (IOException | RuntimeException e) {
                            transfers.cancel(grant.transferId());
                            try { writer.send(new Message(MessageType.ERROR, responseForDownload(grant))); }
                            catch (IOException ignored) { }
                        }
                    });
                }
            }
        } catch (EOFException exception) {
            if (!clientSocket.isClosed()) {
                System.err.println("Client connection ended during an incomplete frame: " + address);
            }
        } catch (IOException exception) {
            if (!clientSocket.isClosed()) {
                System.err.println("Client connection error for " + address + ": " + exception.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException exception) {
                System.err.println("Unable to close client socket for " + address + ": " + exception.getMessage());
            } finally {
                // Close first: a concurrent verified registration must not revive this socket.
                sessionManager.removeSession(clientSocket);
            }
            try {
                onDisconnect.run();
            } finally {
                System.out.println("Client disconnected: " + address);
            }
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode responseForDownload(FileTransferService.Grant grant) {
        return JsonNodeFactory.instance.objectNode().put("code", "FILE_TRANSFER_FAILED")
                .put("message", "Download could not be completed.").put("transferId", grant.transferId().toString());
    }
}
