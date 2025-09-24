package com.example.signalberry;

class MessageItem {
    static final int TYPE_TEXT  = 0;
    static final int TYPE_IMAGE = 1;

    final String from;         // "me" or "peer"
    final int type;            // TYPE_TEXT / TYPE_IMAGE
    final String text;         // for text messages
    final String attachmentId; // for image messages
    final String mime;         // e.g. "image/jpeg"
    int status;                // Chat.ST_*

    // text
    MessageItem(String from, String text, int status) {
        this.from = from;
        this.type = TYPE_TEXT;
        this.text = text;
        this.status = status;
        this.attachmentId = null;
        this.mime = null;
    }

    // image
    MessageItem(String from, String attachmentId, String mime, int status, boolean isImage) {
        this.from = from;
        this.type = TYPE_IMAGE;
        this.text = null;
        this.status = status;
        this.attachmentId = attachmentId;
        this.mime = mime;
    }
}
