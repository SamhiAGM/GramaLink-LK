package lk.gramalink.server.backend;

import com.fasterxml.jackson.databind.node.*;
import lk.gramalink.shared.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;

/** Synthetic TCP demonstration; it is a CLI integration harness, not Ahamad's JavaFX client. */
public final class BackendDemo {
    public static void main(String[] args) throws Exception {
        String host=args.length>0 ? args[0] : "127.0.0.1"; int port=args.length>1 ? Integer.parseInt(args[1]) : 5000;
        try(var citizen=new Client(host,port,"citizen.demo"); var gn=new Client(host,port,"gn.demo");
            var ds=new Client(host,port,"ds.demo"); var admin=new Client(host,port,"admin.demo")) {
            Message created=citizen.call(MessageType.CREATE_REQUEST,payload().put("type","RESIDENCE").put("title","Synthetic demo request")
                    .put("description","Generated demonstration data only").put("division","DEMO-A"));
            String request=created.payload().path("data").path("id").asText();
            System.out.println("Created request "+request);
            byte[] pdf;
            try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) { doc.addPage(new PDPage());doc.save(out);pdf=out.toByteArray(); }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
            Message ready=citizen.call(MessageType.FILE_META,payload().put("direction","UPLOAD").put("requestId",request)
                    .put("fileName","synthetic-demo.pdf").put("mediaType","application/pdf").put("totalBytes",pdf.length).put("sha256",hash));
            UUID transfer=UUID.fromString(ready.payload().path("transferId").asText());
            TransportCodec.writeChunk(citizen.output,new FileChunk(transfer,0,true,pdf));
            Message ack=citizen.read(); Message stored=citizen.read();
            if (!ack.payload().path("complete").asBoolean() || !stored.payload().path("status").asText().equals("STORED")) throw new IOException("Upload did not commit.");
            citizen.call(MessageType.FILE_META,payload().put("direction","DOWNLOAD").put("documentId",transfer.toString()));
            var downloaded=(FileChunk)TransportCodec.readFrame(citizen.input);
            if (!Arrays.equals(pdf,downloaded.data())) throw new IOException("Download integrity mismatch.");
            System.out.println("PDF upload/download verified: "+pdf.length+" bytes, SHA-256 "+hash);
            int version=0;
            for(String action:List.of("RECEIVE","REVIEW","VERIFY","FORWARD")) {
                Message changed=gn.call(MessageType.STATUS_UPDATE,payload().put("requestId",request).put("expectedVersion",version++).put("action",action));
                System.out.println("GN: "+changed.payload().path("data").path("status").asText());
            }
            Message approved=ds.call(MessageType.STATUS_UPDATE,payload().put("requestId",request).put("expectedVersion",version).put("action","APPROVE"));
            System.out.println("DS: "+approved.payload().path("data").path("status").asText());
            System.out.println("History entries: "+citizen.call(MessageType.GET_HISTORY,payload().put("requestId",request)).payload().path("data").size());
            System.out.println("Online connections: "+admin.call(MessageType.GET_STATISTICS,payload()).payload().path("data").path("onlineConnections").asInt());
            citizen.socket.close();
            try(var reconnected=new Client(host,port,"citizen.demo")) {
                System.out.println("Notifications after reconnect: "+reconnected.call(MessageType.GET_NOTIFICATIONS,payload()).payload().path("data").size());
            }
        }
    }
    private static ObjectNode payload() { return JsonNodeFactory.instance.objectNode(); }
    private static final class Client implements AutoCloseable {
        final Socket socket; final DataInputStream input; final DataOutputStream output;
        Client(String host,int port,String user) throws IOException {
            socket=new Socket(host,port); socket.setSoTimeout(15000);
            input=new DataInputStream(socket.getInputStream()); output=new DataOutputStream(socket.getOutputStream());
            try { call(MessageType.LOGIN,payload().put("username",user).put("password","DemoPass123!")); }
            catch(IOException e) {socket.close();throw e;}
        }
        Message read() throws IOException {
            var frame=TransportCodec.readFrame(input);
            if (!(frame instanceof TransportFrame.JsonFrame json)) throw new IOException("Expected JSON reply.");
            if (json.message().type()==MessageType.ERROR) throw new IOException("Demo command failed: "+json.message().payload().path("code").asText());
            return json.message();
        }
        Message call(MessageType type,ObjectNode p) throws IOException {
            String correlation=UUID.randomUUID().toString(); JsonUtil.writeMessage(output,new Message(type,correlation,null,p));
            for(int i=0;i<1000;i++) {Message m=read();if(correlation.equals(m.correlationId())) return m;}
            throw new IOException("No correlated reply received.");
        }
        public void close() throws IOException {socket.close();}
    }
}
