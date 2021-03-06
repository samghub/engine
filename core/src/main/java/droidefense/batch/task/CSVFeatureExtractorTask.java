package droidefense.batch.task;


import apkr.external.module.AtomManifestParser;
import apkr.external.module.batch.base.IBatchTask;
import apkr.external.module.batch.base.ICSVGenerator;
import apkr.external.module.datamodel.manifest.UsesPermission;
import apkr.external.modules.helpers.log4j.Log;
import apkr.external.modules.helpers.log4j.LoggerType;
import droidefense.handler.AXMLDecoderHandler;
import droidefense.handler.DirScannerHandler;
import droidefense.handler.FileIOHandler;
import droidefense.handler.FileUnzipHandler;
import droidefense.handler.base.AbstractHandler;
import droidefense.handler.base.DirScannerFilter;
import droidefense.sdk.helpers.InternalConstant;
import droidefense.sdk.model.base.HashedFile;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * Created by .local on 15/04/2016.
 */
public class CSVFeatureExtractorTask implements IBatchTask, ICSVGenerator, Serializable {

    public static final String OUTPUT_FOLDER_NAME = "batch";

    private static int counter = 0;
    private final File outputDir;
    private final String outputFileName;
    private final String sample_type;
    private File baseFolder;
    private ArrayList<HashedFile> files;
    private ArrayList<File> xmlFiles;
    private boolean cont;

    //result
    private ArrayList<OutPutResult> data;

    public CSVFeatureExtractorTask(File baseFolder, File outputDir, String outputFileName, String sample_type) {
        this.baseFolder = baseFolder;
        this.outputFileName = outputFileName;
        this.sample_type = sample_type;
        this.outputDir = new File(outputDir.getAbsolutePath() + File.separator + OUTPUT_FOLDER_NAME + File.separator + getTaskIdName());
        xmlFiles = new ArrayList<>();
        data = new ArrayList<>();
    }

    @Override
    public void beforeTask() {
        System.out.println("---");
        System.out.println(getTaskName());
        System.out.println("---");
        System.out.println("Loading .apk files to extract features.");
        System.out.println("Running directory scanning on " + baseFolder.getAbsolutePath());
        System.out.println("Running...");
        DirScannerHandler scanner = new DirScannerHandler(baseFolder, false, new DirScannerFilter() {
            @Override
            public boolean addFile(File f) {
                return f.getName().endsWith(InternalConstant.APK_EXTENSION);
            }
        });
        scanner.doTheJob();
        files = scanner.getFiles();
        cont = files != null && !files.isEmpty();
        if (!cont) {
            System.out.println("No files found!");
            return;
        }
    }

    @Override
    public void onTask() {
        createDir();
        if (cont) {
            for (int i = 0; i < files.size(); i++) {
                //only unpacks
                File out = new File(outputDir.getAbsolutePath() + File.separator + i);
                createDir();
                AbstractHandler handler = new FileUnzipHandler(files.get(i), out);
                handler.doTheJob();
                Log.write(LoggerType.TRACE, i);
                Log.write(LoggerType.TRACE, "Listing unpacked files...");
                //get android manifest
                HashedFile manif = new HashedFile(out.getAbsolutePath() + File.separator + InternalConstant.ANDROID_MANIFEST);
                if (manif.getThisFile().exists()) {
                    Log.write(LoggerType.TRACE, "Decoding XML resources");
                    //decode unpacked files
                    ArrayList<HashedFile> onlyManif = new ArrayList<>();
                    onlyManif.add(manif);
                    try {
                        handler = new AXMLDecoderHandler(onlyManif);
                        handler.doTheJob();
                    } catch (Exception e) {
                        Log.write(LoggerType.FATAL, files.get(i).getAbsolutePath(), e.getLocalizedMessage(), e.getStackTrace());
                    }
                    Log.write(LoggerType.TRACE, "Generating file juicy information...");
                    counter++;
                    xmlFiles.add(manif.getThisFile());
                }
            }
        }
    }

    private boolean createDir() {
        boolean ok;
        if (!outputDir.exists())
            ok = outputDir.mkdirs();
        else {
            return true;
        }
        if (!ok)
            throw new IllegalArgumentException("Could not create output directory");
        return ok;
    }

    @Override
    public void afterTask() {
        if (cont) {
            //generate info as csv file
            for (File xml : xmlFiles) {
                if (xml.exists() && xml.isFile() && xml.canRead()) {
                    try {
                        AtomManifestParser parser = new AtomManifestParser();
                        parser.parse(xml);
                        ArrayList<UsesPermission> permList = parser.getManifest().getUsesPermissionList();
                        OutPutResult result = new OutPutResult(permList);
                        data.add(result);
                    } catch (ParserConfigurationException | IOException e) {
                        Log.write(LoggerType.FATAL, xml.getAbsolutePath(), e.getLocalizedMessage(), e.getStackTrace());
                    } catch (Exception e) {
                        Log.write(LoggerType.FATAL, xml.getAbsolutePath(), e.getLocalizedMessage(), e.getStackTrace());
                    }
                }
            }
            toCSV();
            //toCSV();
            System.out.println("Succesfully scanned and extracted features from " + counter + " files.");
            System.out.println("Check output folder for results: " + outputDir.getAbsolutePath());
        }
    }

    @Override
    public String getTaskName() {
        return "AndroidManifest.xml feature extraction task";
    }

    @Override
    public String getTaskIdName() {
        return "perm-feat-extract";
    }

    //CSV inteface methods

    private String toCSVLine(String[] data) {
        String header = "";
        if (data.length > 0) {
            header = data[0];
            for (int i = 1; i < data.length - 1; i++) {
                header += ", " + data[i];
            }
        }
        return header;
    }

    @Override
    public String toCSV() {
        //convert data to csv format
        String[] names = OutPutResult.getGlobalPermissionList();
        String header = "# " + toCSVLine(names) + "\r\n";
        String body = "";
        for (OutPutResult item : data) {
            body += toCSVLine(item.getValues(names)) + "\r\n";
        }
        String out = header + body;
        FileIOHandler.saveFile(outputDir.getParentFile().getAbsolutePath(), outputFileName + ".csv", out.getBytes());
        return out;
    }
}

