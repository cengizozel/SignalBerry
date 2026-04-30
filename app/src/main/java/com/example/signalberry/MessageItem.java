package com.example.signalberry;

class MessageItem {
    static final int TYPE_TEXT  = 0;
    static final int TYPE_IMAGE = 1;

    final String from;
    final int type;
    final String text;
    final String attachmentId;
    final String mime;
    final String caption;
    final String localUri;     // content:// URI for locally-picked images before bridge assigns an ID
    int status;

    long serverTs;      // Signal-level timestamp (ms); 0 if unknown — used for sending quote replies
    String quoteText;   // non-null when this message is a reply to another
    String quoteAuthor; // "me" or "peer", non-null when quoteText != null

    // text
    MessageItem(String from, String text, int status) {
        this.from = from;
        this.type = TYPE_TEXT;
        this.text = text;
        this.status = status;
        this.attachmentId = null;
        this.mime = null;
        this.caption = null;
        this.localUri = null;
    }

    // image from bridge (with optional caption)
    MessageItem(String from, String attachmentId, String mime, String caption, int status) {
        this.from = from;
        this.type = TYPE_IMAGE;
        this.text = null;
        this.status = status;
        this.attachmentId = attachmentId;
        this.mime = mime;
        this.caption = caption;
        this.localUri = null;
    }

    // image sent locally — show from device URI immediately
    MessageItem(String from, String localUri, String caption, int status, boolean local) {
        this.from = from;
        this.type = TYPE_IMAGE;
        this.text = null;
        this.status = status;
        this.attachmentId = null;
        this.mime = null;
        this.caption = caption;
        this.localUri = localUri;
    }
}
