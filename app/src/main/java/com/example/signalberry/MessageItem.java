package com.example.signalberry;

class MessageItem {
    final String from;   // "me" or "peer"
    final String text;
    final int status;    // ST_PENDING, ST_SENT, ST_DELIVERED, ST_READ

    MessageItem(String from, String text, int status) {
        this.from = from;
        this.text = text;
        this.status = status;
    }
}
