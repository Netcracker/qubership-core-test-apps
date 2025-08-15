package com.netcracker.it.meshtestservicespring.utils;

import java.net.URL;

public interface CloseableUrl extends AutoCloseable {
    URL getUrl();
}
