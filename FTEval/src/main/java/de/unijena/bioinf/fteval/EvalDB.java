package de.unijena.bioinf.fteval;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;

public class EvalDB {

    final File root;

    public EvalDB(File root) {
        this.root = root;
        checkPath();
    }

    File profile(String name) {
        return new File(new File(root, "profiles"), name);
    }

    File profile(File name) {
        return new File(new File(root, "profiles"), removeExtName(name));
    }

    File[] msFiles() {
        return new File(root, "ms").listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".ms");
            }
        });
    }

    File[] dotFiles(String profile) {
        return new File(root, "ms").listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".ms");
            }
        });
    }

    File scoreMatrix(String profile) {
        return new File(profile(profile),"matrix.csv");
    }

    String[] profiles() {
        final File[] files = new File(root, "profiles").listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory() && new File(pathname, "dot").exists();
            }
        });
        final String[] names = new String[files.length];
        for (int i=0; i < names.length; ++i) {
            names[i] = files[i].getName();
        }
        return names;
    }

    String removeExtName(File name) {
        return name.getName().substring(0, name.getName().lastIndexOf('.'));
    }

    private void checkPath() {
        if (!root.exists() || !(new File(root, "profiles").exists()))
            throw new RuntimeException("Path '" + root.getAbsolutePath() + "' is no valid dataset path. Create a new" +
                    " evaluation dataset with\nfteval init <name>");
    }


    public File sdf(String name) {
        return new File(new File(root, "sdf"), removeExtName(new File(name)) + ".sdf");
    }

    public File fingerprint(String name) {
        return new File(new File(new File(root, "fingerprints"), name), "tanimoto.csv");
    }
    public String[] fingerprints() {
        final File[] dirs = new File(root, "fingerprints").listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory() && new File(pathname, "tanimoto.csv").exists();
            }
        });
        final String[] names = new String[dirs.length];
        for (int i=0; i < names.length; ++i) names[i] = dirs[i].getName();
        return names;
    }

    public File[] sdfFiles() {
        return new File(root, "sdf").listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".sdf") && new File(new File(root, "ms"), removeExtName(new File(name)) + ".ms").exists();
            }
        });
    }

    public File[] otherScores() {
        return new File(root, "scores").listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".csv");
            }
        });
    }
}
