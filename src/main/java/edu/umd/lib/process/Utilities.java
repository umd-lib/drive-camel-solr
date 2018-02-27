package edu.umd.lib.process;

public class Utilities {
  /**
   * @param yearNumber
   * @return whether the string is a valid year number
   */
  public static boolean isYear(String yearNumber) {
    try {
      int year = Integer.parseInt(yearNumber);
      return (year >= 0 && year <= 9999);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * @param monthNumber
   * @return whether the string is a valid month number
   */
  public static boolean isMonth(String monthNumber) {
    try {
      int month = Integer.parseInt(monthNumber);
      return (month >= 1 && month <= 12);
    } catch (Exception e) {
      return false;
    }

  }

  /**
   * @param dayNumber
   * @return whether the string is a valid day number
   */
  public static boolean isDay(String dayNumber) {
    try {
      int day = Integer.parseInt(dayNumber);
      return (day >= 1 && day <= 31);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * @param filePath
   * @return the parsed out date in the path
   */
  public static String parseDate(String filePath) {
    String output = null;
    if (filePath.contains("date")) {
      int index;
      String paths[] = filePath.split("/");
      for (index = 0; index < paths.length; index++) {
        if ("date".equals(paths[index])) {
          break;
        }
      }
      if (index < paths.length) {
        final String time = "T00:00:00Z";
        String year = index + 1 < paths.length ? paths[index + 1] : null;
        String month = index + 2 < paths.length ? paths[index + 2] : null;
        String day = index + 3 < paths.length ? paths[index + 3] : null;
        if (isYear(year) && isMonth(month)) {
          output = year + "-" + month;
          if (isDay(day)) {
            output = output + "-" + day + time;
          }
        }
      }
    }
    return output;
  }
}
