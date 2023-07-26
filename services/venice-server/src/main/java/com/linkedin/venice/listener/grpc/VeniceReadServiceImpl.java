package com.linkedin.venice.listener.grpc;

import com.google.protobuf.ByteString;
import com.linkedin.davinci.listener.response.ReadResponse;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.listener.OutboundHttpWrapperHandler;
import com.linkedin.venice.listener.ReadQuotaEnforcementHandler;
import com.linkedin.venice.listener.RouterRequestHttpHandler;
import com.linkedin.venice.listener.StatsHandler;
import com.linkedin.venice.listener.StorageReadRequestsHandler;
import com.linkedin.venice.listener.request.GetRouterRequest;
import com.linkedin.venice.listener.request.MultiGetRouterRequestWrapper;
import com.linkedin.venice.listener.request.RouterRequest;
import com.linkedin.venice.protocols.VeniceClientRequest;
import com.linkedin.venice.protocols.VeniceReadServiceGrpc;
import com.linkedin.venice.protocols.VeniceServerResponse;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class VeniceReadServiceImpl extends VeniceReadServiceGrpc.VeniceReadServiceImplBase {
  private static final Logger LOGGER = LogManager.getLogger(VeniceReadServiceImpl.class);
  List<VeniceGrpcHandler> inboundHandlers;
  // iterate over handlers to utilize existing netty pipeline logic for gRPC requests
  List<VeniceGrpcHandler> outboundHandlers;
  StorageReadRequestsHandler storageReadRequestsHandler;

  public VeniceReadServiceImpl(StorageReadRequestsHandler storageReadRequestsHandler) {
    inboundHandlers = new ArrayList<>();
    outboundHandlers = new ArrayList<>();
    LOGGER.info("Created gRPC Server for VeniceReadService");
    this.storageReadRequestsHandler = storageReadRequestsHandler;
    inboundHandlers.add(storageReadRequestsHandler);
    outboundHandlers.add(storageReadRequestsHandler);
  }

  public VeniceReadServiceImpl(
      StorageReadRequestsHandler storageReadRequestsHandler,
      StatsHandler statsHandler,
      RouterRequestHttpHandler routerRequestHttpHandler,
      OutboundHttpWrapperHandler outboundHttpWrapperHandler,
      ReadQuotaEnforcementHandler readQuotaEnforcementHandler) {

    return;
  }

  public VeniceReadServiceImpl(List<VeniceGrpcHandler> inboundHandlers, List<VeniceGrpcHandler> outboundHandlers) {
    this.inboundHandlers = inboundHandlers;
    this.outboundHandlers = outboundHandlers;
    // get last handler in inboundHandlers
    storageReadRequestsHandler = (StorageReadRequestsHandler) inboundHandlers.get(inboundHandlers.size() - 1);
    LOGGER.info("Created gRPC Server for VeniceReadService");
    LOGGER.info("Inbound Handlers for gRPC service: " + inboundHandlers.toString());
    LOGGER.info("Outbound Handlers for gRPC service: " + outboundHandlers.toString());
  }

  // @Override
  // public void get(VeniceClientRequest request, StreamObserver<VeniceServerResponse> responseObserver) {
  //// VeniceServerResponse.Builder response = null;
  //// GrpcHandlerContext ctx = new GrpcHandlerContext(request, response, responseObserver);
  //// for (VeniceGrpcHandler handler : inboundHandlers) {
  //// handler.grpcRead(ctx);
  //// }
  //// VeniceServerResponse grpcResponse = handleSingleGetRequest(request);
  //// for (VeniceGrpcHandler handler : outboundHandlers) {
  //// handler.grpcWrite(ctx);
  //// }
  //// responseObserver.onNext(grpcResponse);
  //// responseObserver.onCompleted();
  // VeniceServerResponse.Builder responseBuilder = VeniceServerResponse.newBuilder();
  // GrpcHandlerContext ctx = new GrpcHandlerContext(request, responseBuilder, responseObserver);
  //
  //// for (VeniceGrpcHandler handler : inboundHandlers) {
  //// handler.grpcRead(ctx);
  //// }
  ////// storageReadRequestsHandler.grpcRead(ctx);
  //// for (VeniceGrpcHandler handler : outboundHandlers) {
  //// handler.grpcWrite(ctx);
  ////// }
  //// ReadResponse readResponse =
  //// storageReadRequestsHandler.handleGrpcRequest(GetRouterRequest.grpcGetRouterRequest(request));
  ////
  //// int schemaId = readResponse.getResponseSchemaIdHeader();
  //// ByteBuf data = readResponse.getResponseBody();
  // StorageResponseObject responseObject = (StorageResponseObject)
  // storageReadRequestsHandler.handleGrpcRequest(GetRouterRequest.grpcGetRouterRequest(request));
  //
  // int schemaId = responseObject.getResponseSchemaIdHeader();
  // ByteBuf data = responseObject.getResponseBody();
  // byte [] array = new byte[data.readableBytes()];
  // data.getBytes(data.readerIndex(), array);
  //
  // System.out.println("resp: " + new String(array, StandardCharsets.UTF_8));
  //
  // CompressionStrategy compressionStrategy = responseObject.getCompressionStrategy();
  // ValueRecord valueRecord = responseObject.getValueRecord();
  // responseObserver.onNext(
  // VeniceServerResponse.newBuilder()
  // .setData(ByteString.copyFrom(array))
  // .setSchemaId(schemaId)
  // .setCompressionStrategy(compressionStrategy.getValue())
  //// .setIsBatchRequest(false)
  // .build()
  // );
  //
  // responseObserver.onCompleted();
  ////
  //// responseObserver.onNext(
  //// VeniceServerResponse.newBuilder().setData(ByteString.copyFrom(data.array(), 4, data.readableBytes() -
  // 4)).setSchemaId(schemaId).build());
  ////// VeniceServerResponse grpcResponse = handleSingleGetRequest(request);
  ////// System.out.println("grpcResponse: " + grpcResponse);
  ////// responseObserver.onNext(grpcResponse);
  //// responseObserver.onCompleted();
  //
  //// if (ctx.getStatus()) {
  //// responseObserver.onNext(responseBuilder.build());
  //// } else {
  //// responseObserver.onError(new Exception("Error in gRPC request"));
  //// }
  // }

  @Override
  public void get(VeniceClientRequest request, StreamObserver<VeniceServerResponse> responseObserver) {
    handleRequest(request, responseObserver);
  }

  private void handleRequest(VeniceClientRequest request, StreamObserver<VeniceServerResponse> responseObserver) {
    VeniceServerResponse.Builder responseBuilder = VeniceServerResponse.newBuilder();
    GrpcHandlerContext ctx = new GrpcHandlerContext(request, responseBuilder, responseObserver);

    for (VeniceGrpcHandler handler: inboundHandlers) {
      handler.grpcRead(ctx);
    }

    for (VeniceGrpcHandler handler: outboundHandlers) {
      handler.grpcWrite(ctx);
    }

    responseObserver.onNext(ctx.getVeniceServerResponseBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void batchGet(VeniceClientRequest request, StreamObserver<VeniceServerResponse> responseObserver) {
    handleRequest(request, responseObserver);
  }

  private VeniceServerResponse handleSingleGetRequest(VeniceClientRequest request) {
    RouterRequest getRouterRequest = GetRouterRequest.grpcGetRouterRequest(request);

    // StorageResponseObject response =
    // (StorageResponseObject) storageReadRequestsHandler.handleGrpcRequest(getRouterRequest);

    // ValueRecord valueRecord = response.getValueRecord();

    ReadResponse readResponse = storageReadRequestsHandler.handleGrpcRequest(getRouterRequest);
    int schemaId = readResponse.getResponseSchemaIdHeader();
    CompressionStrategy compressionStrategy = readResponse.getCompressionStrategy();

    ByteBuf data = readResponse.getResponseBody();

    // ByteString googleByteString = data;
    return VeniceServerResponse.newBuilder().setData(ByteString.copyFrom(data.array())).setSchemaId(schemaId).build();
  }

  private VeniceServerResponse handleMultiGetRequest(VeniceClientRequest request) {
    MultiGetRouterRequestWrapper multiGetRouterRequestWrapper =
        MultiGetRouterRequestWrapper.parseMultiGetGrpcRequest(request);

    ReadResponse readResponse = storageReadRequestsHandler.handleGrpcRequest(multiGetRouterRequestWrapper);
    int schemaId = readResponse.getResponseSchemaIdHeader();
    ByteBuf data = readResponse.getResponseBody();

    return VeniceServerResponse.newBuilder().setData(ByteString.copyFrom(data.array())).setSchemaId(schemaId).build();
  }
}
