//package com.example.yukiai;
//
//import fi.iki.elonen.NanoHTTPD;
//import org.json.JSONObject;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.CompletableFuture;
//
//public class YukiServer extends NanoHTTPD {
//    private final YukiBrainManager brain;
//    private final YukiLocalVision vision;
//
//    public YukiServer(int port, YukiBrainManager brain, YukiLocalVision vision) {
//        super(port);
//        this.brain = brain;
//        this.vision = vision;
//    }
//
//    @Override
//    public Response serve(IHTTPSession session) {
//        if (Method.POST.equals(session.getMethod()) && "/v1/chat".equals(session.getUri())) {
//            try {
//                // Читаем тело запроса
//                Map<String, String> files = new HashMap<>();
//                session.parseBody(files);
//                String postData = files.get("postData");
//                JSONObject json = new JSONObject(postData);
//
//                String userMessage = json.optString("message", "");
//
//                // Получаем текущие объекты из зрения
//                String visionContext = String.join(", ", vision.getCurrentObjects());
//
//                // Используем CompletableFuture, чтобы дождаться ответа от нейронки
//                CompletableFuture<String> futureResponse = new CompletableFuture<>();
//
//                brain.askYuki(userMessage, visionContext, new YukiBrainManager.BrainCallback() {
//                    @Override
//                    public void onThinking() {}
//
//                    @Override
//                    public void onResponse(String text) {
//                        futureResponse.complete(text);
//                    }
//
//                    @Override
//                    public void onError(String error) {
//                        futureResponse.complete("Ошибка: " + error);
//                    }
//                });
//
//                String aiResult = futureResponse.get(); // Блокируем поток сервера до получения ответа
//
//                JSONObject responseJson = new JSONObject();
//                responseJson.put("status", "success");
//                responseJson.put("reply", aiResult);
//                responseJson.put("detected_objects", visionContext);
//
//                return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString());
//
//            } catch (Exception e) {
//                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
//            }
//        }
//        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
//    }
//}