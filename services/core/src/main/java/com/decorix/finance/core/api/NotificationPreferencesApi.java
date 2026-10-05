package com.decorix.finance.core.api;

public final class NotificationPreferencesApi {
    private NotificationPreferencesApi() {}

    public record UpdateRequest(String language, Boolean dailyEnabled, String dailyLocalTime,
            Boolean weeklyEnabled, Integer weeklyDayOfWeek, String weeklyLocalTime,
            String quietHoursStart, String quietHoursEnd) {}

    public record PreferencesResponse(String timezone, boolean telegramLinked, String language,
            boolean dailyEnabled, String dailyLocalTime, boolean weeklyEnabled, int weeklyDayOfWeek,
            String weeklyLocalTime, String quietHoursStart, String quietHoursEnd, long version) {}
}
