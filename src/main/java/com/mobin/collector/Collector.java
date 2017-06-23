package com.mobin.collector;

import com.mobin.common.SmallLRUCache;
import com.mobin.collector.FSUtils.*;
import com.mobin.config.Config;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.tools.cmd.gen.AnyVals;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Filter;

/**
 * Created by Mobin on 2017/5/7.
 * 采集类
 */
public abstract  class Collector implements  Runnable {
    static final Logger log = LoggerFactory.getLogger(Collector.class);
    static final String DONE = ".done";
    static final String DOWN = ".down";

    FileSystem fs;
    String collectorPath;
    String srcPath;
    String type;
    CollectorOptions options;

    public static final String NEW_FILES = "_NEW_FILES_";
    public static final String _COPIED_FILES_ = "_COPIED_FILES_";

    public abstract Map<String, ArrayList<CollectFile>> getNewFiles();

    public abstract String getFileDateTime(String fileName);

    static final FilenameFilter downFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith("DOWN");
        }
    };

    private Thread shutdownHook;
    private volatile boolean shutdown;
    @Override
    public void run() {
          shutdownHook = new Thread(){
              @Override
              public void run() {
                  //如果JVM被强制关闭，首先会执行该run方法
                  Collector.this.shutdown = true;
                  awaitFinsh();
              }
          };
        System.out.println(Thread.currentThread().getName());
        //注册钩子，保证程序能正常执行完
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            copyFile();
        }finally {
            notifyFinish();
        }
    }

    private synchronized void awaitFinsh(){
        log.info("awaiting copy action" + this + "finish");
        try {
            wait();
            log.info(this + " finish");
        } catch (InterruptedException e) {
            log.error("await " + this + " finish exception", e);
        }
    }

    private void copyFile(){
        System.out.println("copyFiles");
        System.out.println(type);
    }

    private synchronized void notifyFinish() {
        if (!shutdown) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        notify();
    }

    protected Map<String, ArrayList<CollectFile>> getNewFiles(String dir, FilenameFilter filter){
        ArrayList<String> list = new ArrayList<>(1);
        list.add(dir);
        return getNewFiles(list, filter);
    }

    public List<String> getDateDir(){
        if (collectorPath == null || collectorPath.isEmpty()) {
            return new ArrayList<>(0);
        }
        String[] dirs = collectorPath.split(",");
        return getDateDir(dirs, Config.dateFormat);
    }

    public List<String> getDateDir(String[] dirs, SimpleDateFormat dateFormat){

    }



    protected Map<String, ArrayList<CollectFile>> getNewFiles(List<String> dirs, FilenameFilter filter){
        dirs = getModifiedDirs(dirs);    //需要copy的文件队列
        log.info("modified dirs:" + dirs);
        Map<String, ArrayList<CollectFile>> dateTimeToNewFilesMap = new TreeMap<>();
        Map<String, HashSet<String>> dateTimeToCopiedFilesMap = new TreeMap<>();
        Map<String, AtomicLong> dateTimeToFileIdMap = new TreeMap<>();

        for (String d : dirs) {
            File dir = new File(d);
            File[] files;
            if (filter != null){
                files = dir.listFiles(filter);
            } else {
                files = dir.listFiles();
            }
            if (files == null || files.length == 0) {
                continue;
            }

            log.info("dir: " + dir + ", files:" + files.length);
            for (File f : files) {
                if (!isCopyableFile(f)) {  //延迟判断文件是否已经写完，如果目录时间戳在延迟间隔内不改，必需删除目录缓存
                     removeDirCache(d);
                } else {
                    String name = f.getName();
                    String dateTime;
                    try {
                        dateTime = getFileDateTime(name);
                    }catch (Exception e){
                        log.warn("文件名不包含日期时间，可能是一个无效的文件或文件名");
                        continue;
                    }
                    if (options.dateTime != null) {
                        if (!options.dateTime.equals(dateTime)) {
                            continue;
                        }
                    } else if (options.startTime != null) {
                        if (!options.startTime.equals(dateTime)) {
                            continue;
                        }
                    }
                    //文件已经入库过
                    if (isCopied(dateTime, f , dateTimeToCopiedFilesMap, dateTimeToFileIdMap)) {
                        continue;
                    }
                    //新文件
                    String date = dateTime.substring(0, dateTime.length() -2);
                    String srcDir = srcPath + date + "/" + dateTime + "/";
                    ArrayList<CollectFile> newFiles = dateTimeToNewFilesMap.get(dateTime);
                    if (newFiles == null) {
                        newFiles = new ArrayList<>();
                        dateTimeToNewFilesMap.put(dateTime, newFiles);
                    }
                    newFiles.add(new CollectFile(fs, f, srcDir, -1));
                }
            }
            return  dateTimeToNewFilesMap;
        }
        return null;
    }

    private boolean isCopied(String dateTime, File f, Map<String, HashSet<String>> dateTimeToCopiedFilesMap, Map<String, AtomicLong> dateTimeToFileIdMap) {
        HashSet<String> copiedFiles = dateTimeToCopiedFilesMap.get(dateTime);
        if (copiedFiles == null) {  //新文件
            try {
                copiedFiles = readCopiedFiles(dateTime, dateTimeToFileIdMap);
                dateTimeToCopiedFilesMap.put(dateTime, copiedFiles);
            } catch (IOException e) {
                log.error("Failed to readCopiedFiles, dateTime: " + dateTime, e);
                return false;
            }
        }
        return copiedFiles.contains(f.getAbsolutePath());
    }

    private HashSet<String> readCopiedFiles(String dateTime, Map<String, AtomicLong> dateTimeToFileIdMap) throws IOException {
        String copiedFileName = getCopiedFileName(dateTime);
        HashSet<String> copiedFiles = new HashSet<>();
        if (!fs.exists(new Path(copiedFileName))) {  //判断该路径下是否存在该文件，如果不存在
            dateTimeToFileIdMap.put(dateTime, new AtomicLong(0));
            return copiedFiles;
        }
        //如果存在,就读取文件中的内容
        try(BufferedReaderIterable bri = FSUtils.createBufferedReadIterable(fs, copiedFileName)){
            for (String line : bri) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] a = line.split(",", -1);
                copiedFiles.add(a[0]);
                bri.incrementVaildRecords();
            }
            dateTimeToFileIdMap.put(dateTime, new AtomicLong(bri.getVaildRecords()));
        }
      return copiedFiles;
    }

    private String getCopiedFileName(String dateTime) {
        String date = dateTime.substring(0, dateTime.length() - 2);
        //srcPath + _COPIED_FILES_ + "/" + date + "/" + dateTime + ".txt"文件保存的是文件的路径
        return srcPath + _COPIED_FILES_ + "/" + date + "/" + dateTime + ".txt";
    }


    private boolean isCopyableFile(File f) {
        long lastModifiedTime = f.lastModified();
        if (f.isFile() && f.length() >0 && lastModifiedTime + 2 * 60 * 1000 < System.currentTimeMillis()) {  //没有新文件或文件没有被修改
            return true;
        }
        return false;
    }

    //非线程安全，需要同步
    private static final SmallLRUCache<String, Long> dirCache = new SmallLRUCache<>(500);

    private void removeDirCache(String dir) {
        synchronized (dirCache) {
            dirCache.remove(dirCache.get(dir));
        }
    }

    private List<String> getModifiedDirs(List<String> dirs) {
        List<String> modifiedeDirs = new ArrayList<>();
        for (String dir: dirs) {
            File d = new File(dir);
            if (!d.exists()) {
                log.warn("Dir not exists : " + dir);
                continue;
            }

            synchronized (dirCache) {
                Long lastModified  = dirCache.get(dir); //获取文件的时间戳（缓存值）
                long time = d.lastModified();  // （实时值）
                if (lastModified == null || time > lastModified) {  //说明有新文件或文件有更新
                    modifiedeDirs.add(dir);
                    dirCache.put(dir, time);
                }
            }
        }
        return modifiedeDirs;
    }
}
