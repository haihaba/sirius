package de.unijena.bioinf.sirius.projectspace;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SiriusWorkspaceWriter implements DirectoryWriter.WritingEnvironment {

    protected ZipOutputStream zip;
    protected List<String> pathElements;

    public SiriusWorkspaceWriter(OutputStream stream) {
        this.zip = new ZipOutputStream(stream, Charset.forName("UTF-8"));
        this.pathElements = new ArrayList<>();
    }

    @Override
    public void enterDirectory(String name) {
        pathElements.add(name);
        System.out.println("ENTER DIRECTORY " + join(pathElements));
        try {
            zip.putNextEntry(new ZipEntry(join(pathElements)));
            zip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String join(List<String> pathElements) {
        StringBuilder buf = new StringBuilder(pathElements.size()*8);
        for (String p : pathElements) buf.append(p).append('/');
        return buf.toString();
    }

    @Override
    public OutputStream openFile(String name) {
        System.out.println("OPEN FILE " + (join(pathElements) + name));
        try {
            zip.putNextEntry(new ZipEntry(join(pathElements) + name));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return zip;
    }

    @Override
    public void closeFile() {
        System.out.println("CLOSE FILE");
        try {
            zip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

    @Override
    public void leaveDirectory() {
        System.out.println("LEAVE DIRECTORY");
        pathElements.remove(pathElements.size()-1);
    }

    @Override
    public void close() throws IOException {
        System.out.println("CLOSE STREAM");
        zip.close();
    }
}
