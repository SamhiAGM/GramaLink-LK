package lk.gramalink.shared;

import java.util.Objects;

/** The two frame forms accepted on a GramaLink TCP connection. */
public sealed interface TransportFrame permits TransportFrame.JsonFrame, FileChunk {
    record JsonFrame(Message message) implements TransportFrame {
        public JsonFrame {
            Objects.requireNonNull(message, "JSON message is required.");
        }
    }
}
