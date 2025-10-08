package com.netcracker.cloud.meshtestservicespring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.meshtestservicespring.model.TraceResponse;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

@Slf4j
@AllArgsConstructor
public class TcpService {

    private final HelloService helloService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        Thread thread = new Thread(this::startServer);
        thread.start();
    }

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(8082)) {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleRequest(clientSocket);
                } catch (Exception e) {
                    log.error("Found error", e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRequest(Socket clientSocket) throws IOException {
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        String inputLine = in.readLine();
        inputLine = inputLine.replace("\r\n", "");
        log.info("Hello from tcp server. Your message: {}. Your ip: {}", inputLine, clientSocket.getRemoteSocketAddress());

        TraceResponse traceResponse = helloService.hello();
        traceResponse.setRequestMessage(inputLine);
        traceResponse.setRequestHost(clientSocket.getRemoteSocketAddress().toString());

        out.println(objectMapper.writeValueAsString(traceResponse));
    }
}
