package lk.gramalink.client;

import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ClientFilesTest {
    @TempDir Path directory;
    @Test void transportAckAloneDoesNotCompleteUploadAndDisconnectNeverReplaysIt() throws Exception {
        var fileRef=new AtomicReference<ClientFiles>();var progressRef=new AtomicReference<FileTransferProgress>();
        try(ServerSocket listener=new ServerSocket(0);
            NetworkClient client=new NetworkClient("127.0.0.1",listener.getLocalPort(),new NetworkClient.Listener(){
                public void onMessage(Message m){try{progressRef.get().acceptControl(1,m);fileRef.get().control(m);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}
                public void onStatus(NetworkClient.Status status){if(status==NetworkClient.Status.DISCONNECTED && fileRef.get()!=null)fileRef.get().disconnected();}
            },Runnable::run)){
            var progress=new FileTransferProgress(()->true,()->1,Runnable::run,ignored->{});progressRef.set(progress);
            try(var files=new ClientFiles(client,progress,()->1,()->true)){
                fileRef.set(files);client.connect();
                try(Socket peer=listener.accept()){
                    peer.setSoTimeout(3000);var input=new DataInputStream(peer.getInputStream());var output=new DataOutputStream(peer.getOutputStream());
                    Path pdf=directory.resolve("fixture.pdf");Files.writeString(pdf,"Synthetic transport fixture, not a valid application PDF");
                    var result=files.upload(UUID.randomUUID().toString(),pdf);
                    var duplicate=assertThrows(ExecutionException.class,()->files.upload("request",pdf).get(3,TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class,duplicate.getCause(),"A pending upload must not be started twice");
                    uploadPreflight(input,output,"SUBMITTED");
                    Message metadata=JsonUtil.readMessage(input);UUID id=UUID.randomUUID();
                    JsonUtil.writeMessage(output,new Message(MessageType.FILE_META,metadata.correlationId(),null,BackendQueries.object().put("status","READY").put("direction","UPLOAD").put("transferId",id.toString()).put("totalBytes",Files.size(pdf)).put("maxChunkBytes",FileChunk.MAX_CHUNK_BYTES)));
                    var chunk=assertInstanceOf(FileChunk.class,TransportCodec.readFrame(input));
                    JsonUtil.writeMessage(output,new Message(MessageType.ACK,BackendQueries.object().put("transferId",id.toString()).put("sequence",chunk.sequence()).put("receivedBytes",chunk.data().length).put("complete",true).put("stage","TRANSPORT_RECEIVED")));
                    assertThrows(TimeoutException.class,()->result.get(150,TimeUnit.MILLISECONDS),"No STORED confirmation exists");
                    peer.close();assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));
                }
            }
        }
    }
    @Test void preflightRequiresAuthenticatedSessionWithoutSendingMetadata() throws Exception {
        try(var client=new NetworkClient();var files=new ClientFiles(client,new FileTransferProgress(()->false,()->1,Runnable::run,ignored->{}),()->1,()->false)){
            var failure=assertThrows(ExecutionException.class,()->files.upload("request",directory.resolve("unreadable.exe")).get(3,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,failure.getCause());assertFalse(client.isConnected());
        }
    }
    private static void uploadPreflight(DataInputStream input,DataOutputStream output,String status) throws Exception {
        Message request=JsonUtil.readMessage(input);
        assertEquals(MessageType.GET_REQUEST,request.type());
        var payload=BackendQueries.object().put("operation","GET_REQUEST");
        payload.putObject("data").put("id",request.payload().path("requestId").asText()).put("status",status);
        JsonUtil.writeMessage(output,new Message(MessageType.ACK,request.correlationId(),null,payload));
        if(ClientFiles.uploadsAllowed(status)) {
            Message list=JsonUtil.readMessage(input);assertEquals(MessageType.LIST_DOCUMENTS,list.type());
            var listed=BackendQueries.object().put("operation","LIST_DOCUMENTS");listed.putArray("data");
            JsonUtil.writeMessage(output,new Message(MessageType.ACK,list.correlationId(),null,listed));
        }
    }

    @Test void explicitNegotiationRejectionsDoNotDisconnectOrExhaustNegotiationSlots() throws Exception {
        var fileRef=new AtomicReference<ClientFiles>();var progressRef=new AtomicReference<FileTransferProgress>();
        try(ServerSocket listener=new ServerSocket(0);
            NetworkClient client=new NetworkClient("127.0.0.1",listener.getLocalPort(),new NetworkClient.Listener(){
                public void onMessage(Message m){try{progressRef.get().acceptControl(1,m);fileRef.get().control(m);}catch(IOException e){throw new UncheckedIOException(e);}}
            },Runnable::run)) {
            var progress=new FileTransferProgress(()->true,()->1,Runnable::run,ignored->{});progressRef.set(progress);
            try(var files=new ClientFiles(client,progress,()->1,()->true)) {
                fileRef.set(files);client.connect();
                try(Socket peer=listener.accept()) {
                    peer.setSoTimeout(3000);var input=new DataInputStream(peer.getInputStream());var output=new DataOutputStream(peer.getOutputStream());
                    Path pdf=directory.resolve("bus_system_cpp.pdf");Files.write(pdf,new byte[332049]);
                    for(int attempt=0;attempt<5;attempt++) {
                        var result=files.upload("request-id",pdf);
                        uploadPreflight(input,output,"SUBMITTED");
                        Message metadata=JsonUtil.readMessage(input);assertEquals(MessageType.FILE_META,metadata.type());
                        assertEquals("application/pdf",metadata.payload().path("mediaType").asText());
                        assertEquals(332049,metadata.payload().path("totalBytes").asInt());
                        assertTrue(metadata.payload().path("sha256").asText().matches("[a-f0-9]{64}"));
                        JsonUtil.writeMessage(output,new Message(MessageType.ERROR,metadata.correlationId(),null,
                                BackendQueries.object().put("code","FILE_TRANSFER_REJECTED").put("message","The backend rejected the file transfer.")));
                        var error=assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));
                        assertTrue(error.getCause().getMessage().contains("before READY"));
                        assertTrue(client.isConnected(),"A backend refusal must not revoke the session");
                    }
                    var query=BackendQueries.command(MessageType.ME,BackendQueries.object());
                    var result=client.request(query,java.time.Duration.ofSeconds(3));
                    Message received=JsonUtil.readMessage(input);assertEquals(MessageType.ME,received.type(),"No upload chunks or automatic replay");
                    JsonUtil.writeMessage(output,new Message(MessageType.ACK,received.correlationId(),null,BackendQueries.object()));
                    assertEquals(MessageType.ACK,result.get(3,TimeUnit.SECONDS).type());
                }
            }
        }
    }

    @Test void terminalRequestIsRejectedBeforeFileNegotiationWithoutDisconnecting() throws Exception {
        try(ServerSocket listener=new ServerSocket(0);NetworkClient client=new NetworkClient("127.0.0.1",listener.getLocalPort())) {
            client.connect();
            try(Socket peer=listener.accept();var files=new ClientFiles(client,new FileTransferProgress(()->true,()->1,Runnable::run,ignored->{}),()->1,()->true)) {
                peer.setSoTimeout(3000);var input=new DataInputStream(peer.getInputStream());var output=new DataOutputStream(peer.getOutputStream());
                Path pdf=directory.resolve("fixture.pdf");Files.writeString(pdf,"Fixture content is never transferred");
                for(String state:List.of("APPROVED","REJECTED")) {
                    var result=files.upload("request-id",pdf);uploadPreflight(input,output,state);
                    var error=assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));
                    assertTrue(error.getCause().getMessage().contains("APPROVED or REJECTED"));assertTrue(client.isConnected());
                }
            }
        }
    }
}
