package com.aiworld.dialog;

/**
 * A single recorded conversation between two NPCs.
 * Stored in Memory's conversation log so NPCs can reference
 * past exchanges when the LLM builds future dialog prompts.
 */
public class DialogSession {

    private final long   tick;
    private final String speakerId;
    private final String listenerId;
    private final String speakerLine;
    private final String listenerLine;
    private final double valence;   // -1.0 (hostile) to +1.0 (warm)

    public DialogSession(long tick, String speakerId, String listenerId,
                         String speakerLine, String listenerLine, double valence) {
        this.tick         = tick;
        this.speakerId    = speakerId;
        this.listenerId   = listenerId;
        this.speakerLine  = speakerLine;
        this.listenerLine = listenerLine;
        this.valence      = valence;
    }

    public long   getTick()         { return tick; }
    public String getSpeakerId()    { return speakerId; }
    public String getListenerId()   { return listenerId; }
    public String getSpeakerLine()  { return speakerLine; }
    public String getListenerLine() { return listenerLine; }
    public double getValence()      { return valence; }

    @Override
    public String toString() {
        return String.format("[tick %d] %s: \"%s\" | %s: \"%s\"",
            tick, speakerId, speakerLine, listenerId, listenerLine);
    }
}
