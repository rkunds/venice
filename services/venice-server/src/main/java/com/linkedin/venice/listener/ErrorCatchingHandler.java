package com.linkedin.venice.listener;

import com.linkedin.venice.listener.grpc.GrpcHandlerContext;
import com.linkedin.venice.listener.grpc.VeniceGrpcHandler;
import com.linkedin.venice.listener.response.HttpShortcutResponse;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponseStatus;


/***
 * Expects a GetRequestObject which has store name, key, and partition
 * Queries the local store for the associated value
 * writes the value (as a byte[]) back down the stack
 */
@ChannelHandler.Sharable
public class ErrorCatchingHandler extends ChannelInboundHandlerAdapter implements VeniceGrpcHandler {
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    ctx.writeAndFlush(new HttpShortcutResponse(cause.getMessage(), HttpResponseStatus.INTERNAL_SERVER_ERROR));
    ctx.close();
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
  }

  @Override
  public void grpcRead(GrpcHandlerContext ctx) {
    return;
  }

  @Override
  public void grpcWrite(GrpcHandlerContext ctx) {
    return;
  }
}
