package com.voicemail.tracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a recurring email report subscription.
 * Stored and persisted by {@link SubscriptionStore}.
 */
public class SubscriptionRecord {

    private String id;
    private String name = "";
    private List<String> recipients = new ArrayList<>();
    private String schedule = "DAILY";
    private String scheduleDay = "MON";
    private String scheduleTime = "08:00";

    /**
     * IANA timezone identifier for this subscription's schedule.
     * E.g. "America/Denver", "America/Los_Angeles", "America/New_York".
     * Defaults to Mountain Time if not specified.
     */
    private String timezone = "America/Denver";

    private String viewMode = "queues";
    private String period = "30d";
    private String format = "CSV";
    private boolean enabled = true;
    private String createdAt = "";
    private List<String> selectedUserIds = new ArrayList<>();
    private List<String> selectedQueueIds = new ArrayList<>();

    public String getId()                        { return id; }
    public void   setId(String id)               { this.id = id; }

    public String getName()                      { return name; }
    public void   setName(String v)              { this.name = v; }

    public List<String> getRecipients()          { return recipients; }
    public void   setRecipients(List<String> v)  { this.recipients = v != null ? v : new ArrayList<>(); }

    public String getSchedule()                  { return schedule; }
    public void   setSchedule(String v)          { this.schedule = v; }

    public String getScheduleDay()               { return scheduleDay; }
    public void   setScheduleDay(String v)       { this.scheduleDay = v; }

    public String getScheduleTime()              { return scheduleTime; }
    public void   setScheduleTime(String v)      { this.scheduleTime = v; }

    public String getTimezone()                  { return timezone != null ? timezone : "America/Denver"; }
    public void   setTimezone(String v)          { this.timezone = v; }

    public String getViewMode()                  { return viewMode; }
    public void   setViewMode(String v)          { this.viewMode = v; }

    public String getPeriod()                    { return period; }
    public void   setPeriod(String v)            { this.period = v; }

    public String getFormat()                    { return format; }
    public void   setFormat(String v)            { this.format = v; }

    public boolean isEnabled()                   { return enabled; }
    public void    setEnabled(boolean v)         { this.enabled = v; }

    public String getCreatedAt()                 { return createdAt; }
    public void   setCreatedAt(String v)         { this.createdAt = v; }

    public List<String> getSelectedUserIds()               { return selectedUserIds; }
    public void         setSelectedUserIds(List<String> v) { this.selectedUserIds  = v != null ? v : new ArrayList<>(); }

    public List<String> getSelectedQueueIds()               { return selectedQueueIds; }
    public void         setSelectedQueueIds(List<String> v) { this.selectedQueueIds = v != null ? v : new ArrayList<>(); }
}
