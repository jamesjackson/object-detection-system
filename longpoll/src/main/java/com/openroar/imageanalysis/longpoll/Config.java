package com.openroar.imageanalysis.longpoll;

import java.io.FileInputStream;
import java.util.Properties;

/**
 * Created by jamesjackson on 2/27/17.
 */
public class Config {

    private static Properties defaultProps = new Properties();
    static {
        try {
            FileInputStream in = new FileInputStream("config.properties");
            defaultProps.load(in);
            in.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static String getProperty(String key) {
        return defaultProps.getProperty(key);
    }

}

