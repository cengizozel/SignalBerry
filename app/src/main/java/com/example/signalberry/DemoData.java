package com.example.signalberry;

class DemoData {

    static final String[] NAMES = {
        "Maya Patel",
        "Oliver Chen",
        "Sofia Ramirez",
        "Noah Williams",
        "Amara Okafor",
        "Liam Andersson"
    };

    private static final String[][] DIRS = {
        {
            "in", "out", "in", "in", "out", "in", "out"
        },
        {
            "out", "in", "out", "in", "in", "out", "in", "in"
        },
        {
            "in", "out", "in", "out", "in", "out", "out", "in", "out"
        },
        {
            "out", "in", "out", "in", "in", "out", "in", "out", "in"
        },
        {
            "in", "in", "out", "in", "out", "in", "out", "in"
        },
        {
            "out", "in", "out", "in", "in", "out", "in", "out", "in", "out"
        }
    };

    private static final String[][] TEXTS = {
        {
            "Is Saturday still good for the market?",
            "Yes, late morning works best for me.",
            "Perfect, I will bring the tote bags.",
            "Maybe we can grab coffee first?",
            "Definitely. Coffee before errands sounds right.",
            "Great 👍 10:30 at the corner cafe?",
            "Done. I will meet you outside 😊"
        },
        {
            "I saw your trip photos. The mountains looked unreal.",
            "They were. My legs are still recovering.",
            "Worth it?",
            "Absolutely. Also had the best noodles after.",
            "A truly memorable lunch.",
            "I respect a strong food review.",
            "I will send the place if you ever go.",
            "You would really enjoy it."
        },
        {
            "Small update: I got the apartment 🎉",
            "That is huge news. Congratulations!",
            "Thank you. I am still processing it.",
            "When do you move in?",
            "The first weekend of next month.",
            "I can help with boxes if needed.",
            "Especially if pizza is involved.",
            "Deal. Pizza and sincere gratitude.",
            "That payment plan is acceptable."
        },
        {
            "Can you review the slides before 4?",
            "Yes, I am opening them now.",
            "Mainly worried about the timeline page.",
            "Agreed, that one feels a bit packed.",
            "I will trim the bullets.",
            "Thanks. Please leave the chart in.",
            "Got it, the chart stays.",
            "Can you send the final link when done?",
            "Will do shortly."
        },
        {
            "This dog video deserves your attention.",
            "Sending it now.",
            "The tiny rain boots are excellent.",
            "Right? The confidence is impressive.",
            "I watched it twice. No regrets.",
            "Same. Productivity briefly declined.",
            "Reasonable outcome, honestly.",
            "Adding it to the emergency joy folder 😊"
        },
        {
            "Just checking in. How is your week going?",
            "A bit busy, but nothing too dramatic.",
            "Anything I can help with?",
            "Mostly just too many small things at once.",
            "But I appreciate you asking.",
            "Of course. Want to take a walk later?",
            "Actually yes, that sounds helpful.",
            "Great, around 7?",
            "7 works. Slow pace preferred.",
            "Approved. No speed walking today."
        }
    };

    static String fakeName(String peerKey) {
        int chatIndex = chatIndexFor(peerKey);
        return NAMES[chatIndex];
    }

    static java.util.List<MessageItem> getFakeMessages(int idx) {
        int i2 = Math.floorMod(idx, NAMES.length);
        String[] texts = TEXTS[i2];
        String[] dirs  = DIRS[i2];
        java.util.List<MessageItem> list = new java.util.ArrayList<>();
        long ts = System.currentTimeMillis() - texts.length * 5 * 60000L;
        for (int i = 0; i < texts.length; i++) {
            boolean out = "out".equals(dirs[i]);
            MessageItem m = new MessageItem(out ? "me" : "peer", texts[i], out ? 3 : 0);
            m.serverTs = ts + i * 5 * 60000L;
            list.add(m);
        }
        return list;
    }

    static String fakeSnippetByIndex(int idx) {
        int i2 = Math.floorMod(idx, NAMES.length);
        String[] texts = TEXTS[i2];
        String[] dirs  = DIRS[i2];
        int last = texts.length - 1;
        return "out".equals(dirs[last]) ? "You: " + texts[last] : texts[last];
    }

    static String fakeText(boolean outgoing, int position, String realText) {
        int chatIndex = chatIndexFor(realText);
        return fakeTextForChat(chatIndex, outgoing, position);
    }

    static String fakeSnippetForPeer(String peerKey) {
        int idx = chatIndexFor(peerKey);
        String[] texts = TEXTS[idx];
        String[] dirs  = DIRS[idx];
        int last = texts.length - 1;
        boolean out = "out".equals(dirs[last]);
        return out ? "You: " + texts[last] : texts[last];
    }

    static String fakeSnippet(String snippet) {
        if (snippet == null || snippet.isEmpty()) return "";

        boolean outgoing = snippet.startsWith("You: ");
        String body = outgoing ? snippet.substring(5) : snippet;

        String fake = fakeText(outgoing, Math.abs(body.hashCode()), body);
        return outgoing ? "You: " + fake : fake;
    }

    private static String fakeTextForChat(int chatIndex, boolean outgoing, int position) {
        String wantedDir = outgoing ? "out" : "in";
        String[] dirs = DIRS[chatIndex];
        String[] texts = TEXTS[chatIndex];

        int count = 0;
        for (String d : dirs) if (d.equals(wantedDir)) count++;
        if (count == 0) return texts[Math.floorMod(position, texts.length)];

        int pick = Math.floorMod(position, count);
        int seen = 0;
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i].equals(wantedDir)) {
                if (seen == pick) return texts[i];
                seen++;
            }
        }
        return texts[Math.floorMod(position, texts.length)];
    }

    private static int chatIndexFor(String key) {
        if (key == null || key.isEmpty()) return 0;
        return Math.floorMod(key.hashCode(), NAMES.length);
    }
}