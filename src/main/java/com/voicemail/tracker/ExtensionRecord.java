package com.voicemail.tracker;

/**
 * DTO representing a RingCentral extension (user or call queue).
 */
public class ExtensionRecord {

    private String id;
    private String name;
    private String extensionNumber;
    /** "User", "Department" (call queue), "Announcement", etc. */
    private String type;

    public String getId()                          { return id; }
    public void   setId(String id)                 { this.id = id; }

    public String getName()                        { return name; }
    public void   setName(String v)               { this.name = v; }

    public String getExtensionNumber()             { return extensionNumber; }
    public void   setExtensionNumber(String v)    { this.extensionNumber = v; }

    public String getType()                        { return type; }
    public void   setType(String v)               { this.type = v; }
}
