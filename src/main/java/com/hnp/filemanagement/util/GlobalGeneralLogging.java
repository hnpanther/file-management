package com.hnp.filemanagement.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GlobalGeneralLogging {

    Logger logger = LoggerFactory.getLogger(GlobalGeneralLogging.class);



    public void controllerLogging(int principalId, String principalUsername, String path, String className, String message) {
        String log = "[GlobalGeneralLogging-Controller][class=" + className + "]" + "[username=" + principalUsername + "]" + "[userId=" + principalId + "]" + "[path=" + path + "]: " + message;
        logger.debug(log);
    }

    public void serviceLogging(String methodName, String className, String message) {
        String log = "[GlobalGeneralLogging-Service][class=" + className + "]" + "[method=" + methodName + "]: " + message;
        logger.debug(log);
    }
}
