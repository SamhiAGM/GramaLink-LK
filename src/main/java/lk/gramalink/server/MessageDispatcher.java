package lk.gramalink.server;

import lk.gramalink.shared.Message;
import java.net.Socket;

/** Business-command seam; framing and connection lifecycle remain in ClientHandler. */
@FunctionalInterface
public interface MessageDispatcher {
    Message handle(Socket connection, Message command);
}
