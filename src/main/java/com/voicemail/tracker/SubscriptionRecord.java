package com.voicemail.tracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a recurring email report subscription.
 * Stored in-memory by {@link SubscriptionStore}.
 */
public class SubscriptionRecord {

    /** UUID assigned at creation. */
    private String id;

    /** Human-friendly label, e.g. "Daily Queue Summary". */
    private String name = "";

    /** Comma-separated recipient addresses (stored as a list). */
    private List<String> recipients = new ArrayList<>();

    /** DAILY or WEEKLY */
    private String schedule = "DAILY";

    /**
     * For WEEKLY schedules: the day of the week to send.
     * Values: MON, TUE, WED, THU, FRI, SAT, SUN
     */
    private String scheduleDay = "MON";

    /**
     * Time of day to send, in HH:mm 24-hour format (e.g. "08:00").
     * The scheduler fires hourly and matches on the hour component.
     */
    private String scheduleTime = "08:00";

    /** Which voicemail view to report on: "user", "queues", or "both" */
    private String viewMode = "queues";

    /** Report period: "7d", "30d", "60d", or "90d" */
    private String period = "30d";

    /** Report attachment format: "CSV" or "HTML" (HTML = inline body only, no attachment) */
    private String format = "CSV";

    /** Whether this subscription is currently active. */
    private boolean enabled = true;

    /** ISO-8601 string set at creation time. */
    private String createdAt = "";

    /** Specific user extension IDs to include (empty = all users). */
    private List<String> selectedUserIds = new ArrayList<>();

    /** Specific queue/department extension IDs to include (empty = all queues). */
    private List<String> selectedQueueIds = new ArrayList<>();

    // ── Getters & Setters ────────────────────────────────────────────────────

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
