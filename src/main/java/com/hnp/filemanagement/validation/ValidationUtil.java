package com.hnp.filemanagement.validation;

public class ValidationUtil {


    public static boolean checkCorrectDirectoryName(String directoryName) {
        int count1 = (int) directoryName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) directoryName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) directoryName.chars().filter(ch -> ch == '/').count();
        return count1 == 0 && count2 == 0 && count3 == 0;
    }

    public static boolean checkCorrectFileName(String fileName) {
        int count1 = (int) fileName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) fileName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) fileName.chars().filter(ch -> ch == '/').count();
        return count1 == 1 && count2 == 0 && count3 == 0;
    }
}
