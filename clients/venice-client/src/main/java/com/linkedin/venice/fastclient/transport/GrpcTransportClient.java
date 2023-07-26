package com.linkedin.venice.fastclient.transport;

import com.google.protobuf.ByteString;
import com.linkedin.venice.client.store.transport.TransportClientResponse;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.protocols.VeniceClientRequest;
import com.linkedin.venice.protocols.VeniceReadServiceGrpc;
import com.linkedin.venice.protocols.VeniceServerResponse;
import com.linkedin.venice.request.RequestHelper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class GrpcTransportClient extends InternalTransportClient {
  private static final Logger LOGGER = LogManager.getLogger(GrpcTransportClient.class);

  // field has public visibility for unit testing
  // ideally we re-use stubs and channels whenever possible
  public final Map<String, ManagedChannel> serverGrpcChannels;
  public final Map<ManagedChannel, VeniceReadServiceGrpc.VeniceReadServiceStub> stubCache;
  final Map<String, String> veniceAddressToGrpcAddress;

  public GrpcTransportClient(Map<String, String> nettyServerToGrpc) {
    serverGrpcChannels = new HashMap<>();
    veniceAddressToGrpcAddress = nettyServerToGrpc;
    stubCache = new HashMap<>();

    // for (Map.Entry<String, String> entry : nettyServerToGrpc.entrySet()) {
    // String nettyServer = entry.getKey().split(":")[0];
    // String grpcServer = nettyServer + ":" + entry.getValue();
    //
    // veniceAddressToGrpcAddress.put(entry.getKey(), grpcServer);
    // }

  }

  public ManagedChannel getChannel(String requestPath) {
    String portKey = requestPath.split("/")[2];

    if (!veniceAddressToGrpcAddress.containsKey(portKey)) {
      throw new VeniceException("No grpc server found for port: " + portKey);
    }

    return serverGrpcChannels.computeIfAbsent(
        portKey,
        k -> ManagedChannelBuilder.forTarget(veniceAddressToGrpcAddress.get(k)).usePlaintext().build());
  }

  public VeniceReadServiceGrpc.VeniceReadServiceStub getStubFromChannel(ManagedChannel channel) {
    // if (stubCache.containsKey(channel)) {
    // return stubCache.get(channel);
    // } else {
    // VeniceReadServiceGrpc.VeniceReadServiceStub stub = VeniceReadServiceGrpc.newStub(channel);
    // stubCache.put(channel, stub);
    // return stub;
    // }
    //
    return stubCache.computeIfAbsent(channel, k -> VeniceReadServiceGrpc.newStub(channel));
  }

  @Override
  public CompletableFuture<TransportClientResponse> get(String requestPath, Map<String, String> headers) {
    String[] requestParts = RequestHelper.getRequestParts(requestPath);

    // Lazily create channel for each venice server
    ManagedChannel channel = getChannel(requestPath);

    VeniceClientRequest request = VeniceClientRequest.newBuilder()
        .setStoreName(requestParts[2])
        .setResourceName(requestParts[2])
        .setPartition(Integer.parseInt(requestParts[3]))
        .setKeyString(requestParts[4])
        .setIsBatchRequest(false)
        .build();

    VeniceReadServiceGrpc.VeniceReadServiceStub clientStub = getStubFromChannel(channel);
    GrpcTransportClientCallback callback = new GrpcTransportClientCallback(clientStub, request);

    return callback.get();
  }

  @Override
  public CompletableFuture<TransportClientResponse> post(
      String requestPath,
      Map<String, String> headers,
      byte[] requestBody) {
    String[] requestParts = RequestHelper.getRequestParts(requestPath);
    // print headers:
    for (Map.Entry<String, String> entry: headers.entrySet()) {
      LOGGER.info("Header: " + entry.getKey() + " : " + entry.getValue());
    }
    ManagedChannel channel = getChannel(requestPath);
    VeniceClientRequest request = VeniceClientRequest.newBuilder()
        .setStoreName(requestParts[2])
        .setResourceName(requestParts[2])
        .setKeyBytes(ByteString.copyFrom(requestBody))
        .setIsBatchRequest(true)
        .build();

    VeniceReadServiceGrpc.VeniceReadServiceStub clientStub = getStubFromChannel(channel);
    GrpcTransportClientCallback callback = new GrpcTransportClientCallback(clientStub, request);

    return callback.post();
  }

  @Override
  public void close() throws IOException {
    for (Map.Entry<String, ManagedChannel> entry: serverGrpcChannels.entrySet()) {
      entry.getValue().shutdown();
    }
  }

  public static class GrpcTransportClientCallback {
    // start exception handling
    private final CompletableFuture<TransportClientResponse> valueFuture;
    private final VeniceReadServiceGrpc.VeniceReadServiceStub clientStub;
    private final VeniceClientRequest request;

    public GrpcTransportClientCallback(
        VeniceReadServiceGrpc.VeniceReadServiceStub clientStub,
        VeniceClientRequest request) {
      this.clientStub = clientStub;
      this.request = request;
      this.valueFuture = new CompletableFuture<>();
    }

    public CompletableFuture<TransportClientResponse> get() {
      if (request.getIsBatchRequest()) {
        throw new VeniceException("Not a batch get request, please use batchGet() instead");
      }
      clientStub.get(request, new StreamObserver<VeniceServerResponse>() {
        @Override
        public void onNext(VeniceServerResponse value) {
          System.out.println("Got response from gRPC server");
          valueFuture.complete(
              new TransportClientResponse(
                  value.getSchemaId(),
                  CompressionStrategy.NO_OP,
                  value.getData().toByteArray()));
        }

        @Override
        public void onError(Throwable t) {
          LOGGER.error("Error in gRPC request", t);
          valueFuture.completeExceptionally(t);
        }

        @Override
        public void onCompleted() {
          LOGGER.info("Completed gRPC request");
        }
      });

      return valueFuture;
    }

    public CompletableFuture<TransportClientResponse> post() {
      if (!request.getIsBatchRequest()) {
        throw new VeniceException("Not a batch get request, please use get() instead");
      }
      clientStub.batchGet(request, new StreamObserver<VeniceServerResponse>() {
        @Override
        public void onNext(VeniceServerResponse value) {
          valueFuture.complete(
              new TransportClientResponse(
                  value.getSchemaId(),
                  CompressionStrategy.NO_OP,
                  value.getData().toByteArray()));
          LOGGER.info("Performing BatchGet in gRPC");
        }

        @Override
        public void onError(Throwable t) {
          LOGGER.error("Error in gRPC request", t);
          valueFuture.completeExceptionally(t);
        }

        @Override
        public void onCompleted() {
          LOGGER.info("Completed batch get gRPC request");
        }
      });

      return valueFuture;
    }
  }
}
