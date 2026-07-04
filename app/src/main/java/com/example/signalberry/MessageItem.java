package com.example.signalberry;

class MessageItem {
    static final int TYPE_TEXT        = 0;
    static final int TYPE_IMAGE       = 1;
    static final int TYPE_DATE_HEADER = 2;

    final String from;
    final int type;
    String text;
    final String attachmentId;
    final String mime;
    String caption;
    final String localUri;     // content:// URI for locally-picked images before bridge assigns an ID
    int status;
    final String dateLabel;    // non-null only for TYPE_DATE_HEADER

    long serverTs;      // Signal-level timestamp (ms); negative = pending (-nonce)
    long lastEditTs;    // timestamp of the most recent edit (chained); 0 if never edited
    String quoteText;   // non-null when this message is a reply to another
    String quoteAuthor; // "me" or "peer", non-null when quoteText != null
    String quoteAuthorName; // display name for the quote header, resolved at bind time
    long quoteTs;       // server_ts of the quoted message; 0 if none
    java.util.Map<String, String> reactions; // authorKey → emoji, null if none
    String editHistory; // JSON array of previous texts (oldest first), null if never edited
    String msgType = "text"; // text|image|video|audio|file (media rows share TYPE_IMAGE rendering for now)
    long clientNonce;   // non-zero on rows born from a local send
    String peerKey;     // set only where the caller needs cross-thread context (e.g. report queue)
    String author = "";     // group threads: sender peer key of incoming rows
    String authorName;      // display name (resolved by Chat before render)

    /** Display timestamp: pendings sort at their send moment. */
    long displayTs() { return serverTs < 0 ? (clientNonce > 0 ? clientNonce >> 8 : -serverTs >> 8) : serverTs; }

    // date separator
    MessageItem(String dateLabel, boolean isHeader) {
        this.from = null;
        this.type = TYPE_DATE_HEADER;
        this.text = null;
        this.attachmentId = null;
        this.mime = null;
        this.caption = null;
        this.localUri = null;
        this.status = 0;
        this.dateLabel = dateLabel;
    }

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
        this.dateLabel = null;
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
        this.dateLabel = null;
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
        this.dateLabel = null;
    }
}
