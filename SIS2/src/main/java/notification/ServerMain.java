package notification;

import io.grpc.Server;
import io.grpc.ServerBuilder;

/** Runs the gRPC server (:50051) and the REST baseline (:8080) so you can try them with your own clients. */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        IdempotencyStore grpcStore = new IdempotencyStore();
        Server grpc = ServerBuilder.forPort(50051)
                .addService(new NotificationGrpcService(grpcStore, FaultConfig.NONE)).build().start();
        RestServer rest = new RestServer(8080, new IdempotencyStore());
        System.out.println("gRPC on :" + grpc.getPort() + "  REST on :" + rest.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { grpc.shutdown(); rest.close(); }));
        grpc.awaitTermination();
    }
}
