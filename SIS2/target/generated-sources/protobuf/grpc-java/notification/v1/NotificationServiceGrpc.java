package notification.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * gRPC version of the REST contract from SIS #1:
 *   POST /v1/notification-events      -&gt; SubmitEvent
 *   GET  /v1/notifications/{id}       -&gt; GetNotification
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: notification.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class NotificationServiceGrpc {

  private NotificationServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "notification.v1.NotificationService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<notification.v1.SubmitEventRequest,
      notification.v1.SubmitEventResponse> getSubmitEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SubmitEvent",
      requestType = notification.v1.SubmitEventRequest.class,
      responseType = notification.v1.SubmitEventResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<notification.v1.SubmitEventRequest,
      notification.v1.SubmitEventResponse> getSubmitEventMethod() {
    io.grpc.MethodDescriptor<notification.v1.SubmitEventRequest, notification.v1.SubmitEventResponse> getSubmitEventMethod;
    if ((getSubmitEventMethod = NotificationServiceGrpc.getSubmitEventMethod) == null) {
      synchronized (NotificationServiceGrpc.class) {
        if ((getSubmitEventMethod = NotificationServiceGrpc.getSubmitEventMethod) == null) {
          NotificationServiceGrpc.getSubmitEventMethod = getSubmitEventMethod =
              io.grpc.MethodDescriptor.<notification.v1.SubmitEventRequest, notification.v1.SubmitEventResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SubmitEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  notification.v1.SubmitEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  notification.v1.SubmitEventResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NotificationServiceMethodDescriptorSupplier("SubmitEvent"))
              .build();
        }
      }
    }
    return getSubmitEventMethod;
  }

  private static volatile io.grpc.MethodDescriptor<notification.v1.GetNotificationRequest,
      notification.v1.GetNotificationResponse> getGetNotificationMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetNotification",
      requestType = notification.v1.GetNotificationRequest.class,
      responseType = notification.v1.GetNotificationResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<notification.v1.GetNotificationRequest,
      notification.v1.GetNotificationResponse> getGetNotificationMethod() {
    io.grpc.MethodDescriptor<notification.v1.GetNotificationRequest, notification.v1.GetNotificationResponse> getGetNotificationMethod;
    if ((getGetNotificationMethod = NotificationServiceGrpc.getGetNotificationMethod) == null) {
      synchronized (NotificationServiceGrpc.class) {
        if ((getGetNotificationMethod = NotificationServiceGrpc.getGetNotificationMethod) == null) {
          NotificationServiceGrpc.getGetNotificationMethod = getGetNotificationMethod =
              io.grpc.MethodDescriptor.<notification.v1.GetNotificationRequest, notification.v1.GetNotificationResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetNotification"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  notification.v1.GetNotificationRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  notification.v1.GetNotificationResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NotificationServiceMethodDescriptorSupplier("GetNotification"))
              .build();
        }
      }
    }
    return getGetNotificationMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NotificationServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NotificationServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NotificationServiceStub>() {
        @java.lang.Override
        public NotificationServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NotificationServiceStub(channel, callOptions);
        }
      };
    return NotificationServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NotificationServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NotificationServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NotificationServiceBlockingStub>() {
        @java.lang.Override
        public NotificationServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NotificationServiceBlockingStub(channel, callOptions);
        }
      };
    return NotificationServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NotificationServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NotificationServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NotificationServiceFutureStub>() {
        @java.lang.Override
        public NotificationServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NotificationServiceFutureStub(channel, callOptions);
        }
      };
    return NotificationServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * gRPC version of the REST contract from SIS #1:
   *   POST /v1/notification-events      -&gt; SubmitEvent
   *   GET  /v1/notifications/{id}       -&gt; GetNotification
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Idempotent: the same event_id + same payload may be sent any number of times
     * and results in exactly one enqueue. Same event_id + different payload is
     * rejected with ALREADY_EXISTS (REST 409).
     * </pre>
     */
    default void submitEvent(notification.v1.SubmitEventRequest request,
        io.grpc.stub.StreamObserver<notification.v1.SubmitEventResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubmitEventMethod(), responseObserver);
    }

    /**
     */
    default void getNotification(notification.v1.GetNotificationRequest request,
        io.grpc.stub.StreamObserver<notification.v1.GetNotificationResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetNotificationMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service NotificationService.
   * <pre>
   * gRPC version of the REST contract from SIS #1:
   *   POST /v1/notification-events      -&gt; SubmitEvent
   *   GET  /v1/notifications/{id}       -&gt; GetNotification
   * </pre>
   */
  public static abstract class NotificationServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return NotificationServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service NotificationService.
   * <pre>
   * gRPC version of the REST contract from SIS #1:
   *   POST /v1/notification-events      -&gt; SubmitEvent
   *   GET  /v1/notifications/{id}       -&gt; GetNotification
   * </pre>
   */
  public static final class NotificationServiceStub
      extends io.grpc.stub.AbstractAsyncStub<NotificationServiceStub> {
    private NotificationServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NotificationServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NotificationServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Idempotent: the same event_id + same payload may be sent any number of times
     * and results in exactly one enqueue. Same event_id + different payload is
     * rejected with ALREADY_EXISTS (REST 409).
     * </pre>
     */
    public void submitEvent(notification.v1.SubmitEventRequest request,
        io.grpc.stub.StreamObserver<notification.v1.SubmitEventResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSubmitEventMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getNotification(notification.v1.GetNotificationRequest request,
        io.grpc.stub.StreamObserver<notification.v1.GetNotificationResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetNotificationMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service NotificationService.
   * <pre>
   * gRPC version of the REST contract from SIS #1:
   *   POST /v1/notification-events      -&gt; SubmitEvent
   *   GET  /v1/notifications/{id}       -&gt; GetNotification
   * </pre>
   */
  public static final class NotificationServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<NotificationServiceBlockingStub> {
    private NotificationServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NotificationServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NotificationServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Idempotent: the same event_id + same payload may be sent any number of times
     * and results in exactly one enqueue. Same event_id + different payload is
     * rejected with ALREADY_EXISTS (REST 409).
     * </pre>
     */
    public notification.v1.SubmitEventResponse submitEvent(notification.v1.SubmitEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSubmitEventMethod(), getCallOptions(), request);
    }

    /**
     */
    public notification.v1.GetNotificationResponse getNotification(notification.v1.GetNotificationRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetNotificationMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service NotificationService.
   * <pre>
   * gRPC version of the REST contract from SIS #1:
   *   POST /v1/notification-events      -&gt; SubmitEvent
   *   GET  /v1/notifications/{id}       -&gt; GetNotification
   * </pre>
   */
  public static final class NotificationServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<NotificationServiceFutureStub> {
    private NotificationServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NotificationServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NotificationServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Idempotent: the same event_id + same payload may be sent any number of times
     * and results in exactly one enqueue. Same event_id + different payload is
     * rejected with ALREADY_EXISTS (REST 409).
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<notification.v1.SubmitEventResponse> submitEvent(
        notification.v1.SubmitEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSubmitEventMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<notification.v1.GetNotificationResponse> getNotification(
        notification.v1.GetNotificationRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetNotificationMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SUBMIT_EVENT = 0;
  private static final int METHODID_GET_NOTIFICATION = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SUBMIT_EVENT:
          serviceImpl.submitEvent((notification.v1.SubmitEventRequest) request,
              (io.grpc.stub.StreamObserver<notification.v1.SubmitEventResponse>) responseObserver);
          break;
        case METHODID_GET_NOTIFICATION:
          serviceImpl.getNotification((notification.v1.GetNotificationRequest) request,
              (io.grpc.stub.StreamObserver<notification.v1.GetNotificationResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getSubmitEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              notification.v1.SubmitEventRequest,
              notification.v1.SubmitEventResponse>(
                service, METHODID_SUBMIT_EVENT)))
        .addMethod(
          getGetNotificationMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              notification.v1.GetNotificationRequest,
              notification.v1.GetNotificationResponse>(
                service, METHODID_GET_NOTIFICATION)))
        .build();
  }

  private static abstract class NotificationServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NotificationServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return notification.v1.Notification.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NotificationService");
    }
  }

  private static final class NotificationServiceFileDescriptorSupplier
      extends NotificationServiceBaseDescriptorSupplier {
    NotificationServiceFileDescriptorSupplier() {}
  }

  private static final class NotificationServiceMethodDescriptorSupplier
      extends NotificationServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    NotificationServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (NotificationServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NotificationServiceFileDescriptorSupplier())
              .addMethod(getSubmitEventMethod())
              .addMethod(getGetNotificationMethod())
              .build();
        }
      }
    }
    return result;
  }
}
