package com.linkedin.venice.listener.grpc;

import com.linkedin.davinci.listener.response.ReadResponse;
import com.linkedin.venice.listener.request.RouterRequest;
import com.linkedin.venice.protocols.VeniceClientRequest;
import com.linkedin.venice.protocols.VeniceServerResponse;
import io.grpc.stub.StreamObserver;


public class GrpcHandlerContext {
  // wrapper for objects when passing through the different handlers for the grpc implementation
  boolean isComplete = false;
  private VeniceClientRequest veniceClientRequest;
  // private VeniceServerResponse veniceServerResponse; // ideally we will build the response, and keep editing the
  // response until that time
  private VeniceServerResponse.Builder veniceServerResponseBuilder;
  private StreamObserver<VeniceServerResponse> responseObserver;

  private RouterRequest routerRequest;
  private ReadResponse readResponse; // having both fields is unecessary imo, will see if we can avoid

  public GrpcHandlerContext(
      VeniceClientRequest veniceClientRequest,
      VeniceServerResponse.Builder veniceServerResponseBuilder,
      StreamObserver<VeniceServerResponse> responseObserver) {
    this.veniceClientRequest = veniceClientRequest;
    // this.veniceServerResponse = veniceServerResponse;
    this.veniceServerResponseBuilder = veniceServerResponseBuilder;
    this.responseObserver = responseObserver;
  }

  public VeniceClientRequest getVeniceClientRequest() {
    return veniceClientRequest;
  }

  public void setVeniceClientRequest(VeniceClientRequest veniceClientRequest) {
    this.veniceClientRequest = veniceClientRequest;
  }

  public void setRouterRequest(RouterRequest routerRequest) {
    this.routerRequest = routerRequest;
  }

  public RouterRequest getRouterRequest() {
    return routerRequest;
  }

  // public VeniceServerResponse getVeniceServerResponse() {
  // return veniceServerResponse;
  // }
  //
  // public void setVeniceServerResponse(VeniceServerResponse veniceServerResponse) {
  // this.veniceServerResponse = veniceServerResponse;
  // }

  public ReadResponse getReadResponse() {
    return readResponse;
  }

  public void setReadResponse(ReadResponse readResponse) {
    this.readResponse = readResponse;
  }

  public boolean getStatus() {
    return isComplete;
  }

  public void setStatus(boolean isComplete) {
    this.isComplete = isComplete;
  }

  public VeniceServerResponse.Builder getVeniceServerResponseBuilder() {
    return veniceServerResponseBuilder;
  }

  public void setVeniceServerResponseBuilder(VeniceServerResponse.Builder veniceServerResponseBuilder) {
    this.veniceServerResponseBuilder = veniceServerResponseBuilder;
  }
}
