package com.example.yukiai;


public interface VoiceVoxCallback {
    void onAudioReady();
    void onError(Exception e);
}

