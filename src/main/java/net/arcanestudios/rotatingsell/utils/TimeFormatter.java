package net.arcanestudios.rotatingsell.utils;

import java.util.concurrent.TimeUnit;

/**
 * Utility class for formatting time durations.
 */
public class TimeFormatter {

    /**
     * Formats milliseconds into a human-readable string like "5d 3h 20m".
     *
     * @param milliseconds Duration in milliseconds.
     * @return Formatted string.
     */
    public static String formatDuration(long milliseconds) {
        if (milliseconds <= 0) {
            return "Expired";
        }

        long days = TimeUnit.MILLISECONDS.toDays(milliseconds);
        milliseconds -= TimeUnit.DAYS.toMillis(days);

        long hours = TimeUnit.MILLISECONDS.toHours(milliseconds);
        milliseconds -= TimeUnit.HOURS.toMillis(hours);

        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
        milliseconds -= TimeUnit.MINUTES.toMillis(minutes);

        long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds);

        StringBuilder builder = new StringBuilder();
        if (days > 0) builder.append(days).append("d ");
        if (hours > 0) builder.append(hours).append("h ");
        if (minutes > 0) builder.append(minutes).append("m ");
        
        // Only show seconds if no other units are present
        if (builder.length() == 0) {
            builder.append(seconds).append("s");
        }

        return builder.toString().trim();
    }

    /**
     * Converts a Bukkit Material name to a human-readable display name.
     * e.g., "BAKED_POTATO" → "Baked Potato", "IRON_INGOT" → "Iron Ingot"
     */
    public static String formatItemName(String materialName) {
        if (materialName == null || materialName.isBlank()) return materialName;
        String[] words = materialName.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            result.append(word, 1, word.length());
        }
        return result.toString();
    }

    /**
     * Parses a duration string like "7d", "5h", "30m" into milliseconds.
     *
     * @param duration String duration.
     * @return Duration in milliseconds.
     */
    public static long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) return 0;

        try {
            String clean = duration.trim().toLowerCase();
            char unit = clean.charAt(clean.length() - 1);
            long value = Long.parseLong(clean.substring(0, clean.length() - 1));

            return switch (unit) {
                case 'd' -> TimeUnit.DAYS.toMillis(value);
                case 'h' -> TimeUnit.HOURS.toMillis(value);
                case 'm' -> TimeUnit.MINUTES.toMillis(value);
                case 's' -> TimeUnit.SECONDS.toMillis(value);
                default -> 0;
            };
        } catch (Exception e) {
            return 0;
        }
    }
}
