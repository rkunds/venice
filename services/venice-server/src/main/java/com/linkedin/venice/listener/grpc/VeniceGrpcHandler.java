package com.linkedin.venice.listener.grpc;

public interface VeniceGrpcHandler {
  // used for gRPC replacement for channelReads
  void grpcRead(GrpcHandlerContext ctx);

  // used for gRPC replacement for channelWrites
  void grpcWrite(GrpcHandlerContext ctx);

}
