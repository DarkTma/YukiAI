package com.example.yukiai;

public interface NpcCallback {
    void onUpdate(String partialText);
    void onComplete(String finalText);
    void onError(String errorMsg);
}

