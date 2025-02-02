/**
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.plexus.archiver.jar;

import javax.inject.Named;

import static org.codehaus.plexus.archiver.util.Streams.bufferedOutputStream;
import static org.codehaus.plexus.archiver.util.Streams.fileInputStream;
import static org.codehaus.plexus.archiver.util.Streams.fileOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.parallel.InputStreamSupplier;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ConcurrentJarCreator;
import org.codehaus.plexus.archiver.zip.ZipArchiver;

/**
 * Base class for tasks that build archives in JAR file format.
 */
@Named( "jar" )
public class JarArchiver
    extends ZipArchiver
{

    /**
     * the name of the meta-inf dir
     */
    private static final String META_INF_NAME = "META-INF";

    /**
     * The index file name.
     *
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    private static final String INDEX_NAME = "META-INF/INDEX.LIST";

    /**
     * The manifest file name.
     */
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    /**
     * merged manifests added through addConfiguredManifest
     */
    private Manifest configuredManifest;

    /**
     * shadow of the above if upToDate check alters the value
     */
    private Manifest savedConfiguredManifest;

    /**
     * merged manifests added through filesets
     */
    private Manifest filesetManifest;

    /**
     * Manifest of original archive, will be set to null if not in
     * update mode.
     */
    private Manifest originalManifest;

    /**
     * whether to merge fileset manifests;
     * value is true if filesetmanifest is 'merge' or 'mergewithoutmain'
     */
    private FilesetManifestConfig filesetManifestConfig;

    /**
     * whether to merge the main section of fileset manifests;
     * value is true if filesetmanifest is 'merge'
     */
    private boolean mergeManifestsMain = true;

    /**
     * the manifest specified by the 'manifest' attribute *
     */
    private Manifest manifest;

    /**
     * The file found from the 'manifest' attribute. This can be
     * either the location of a manifest, or the name of a jar added
     * through a fileset. If its the name of an added jar, the
     * manifest is looked for in META-INF/MANIFEST.MF
     */
    private File manifestFile;

    /**
     * jar index is JDK 1.3+ only
     *
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    private boolean index = false;

    /**
     * whether to really create the archive in createEmptyZip, will
     * get set in getResourcesToAdd.
     */
    private boolean createEmpty = false;

    /**
     * Stores all files that are in the root of the archive (i.e. that
     * have a name that doesn't contain a slash) so they can get
     * listed in the index.
     * <p/>
     * Will not be filled unless the user has asked for an index.
     */
    private final List<String> rootEntries;

    /**
     * Path containing jars that shall be indexed in addition to this archive.
     *
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    private List<String> indexJars;

    /**
     * Creates a minimal default manifest with {@code Manifest-Version: 1.0} only.
     */
    private boolean minimalDefaultManifest = false;

    /**
     * constructor
     */
    public JarArchiver()
    {
        super();
        archiveType = "jar";
        setEncoding( "UTF8" );
        rootEntries = new ArrayList<>();
    }

    /**
     * Set whether or not to create an index list for classes.
     * This may speed up classloading in some cases.
     *
     * @param flag true to create an index
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    public void setIndex( boolean flag )
    {
        index = flag;
    }

    /**
     * Set whether the default manifest is minimal, thus having only {@code Manifest-Version: 1.0} in it.
     *
     * @param minimalDefaultManifest true to create minimal default manifest
     */
    public void setMinimalDefaultManifest( boolean minimalDefaultManifest ) {
        this.minimalDefaultManifest = minimalDefaultManifest;
    }

    @SuppressWarnings(
    {
        "JavaDoc", "UnusedDeclaration"
    } )
    @Deprecated // Useless method. Manifests should be UTF-8 by convention. Calling this setter does nothing
    public void setManifestEncoding( String manifestEncoding )
    {
    }

    /**
     * Allows the manifest for the archive file to be provided inline
     * in the build file rather than in an external file.
     *
     * @param newManifest The new manifest
     *
     * @throws ManifestException
     */
    public void addConfiguredManifest( Manifest newManifest )
        throws ManifestException
    {
        if ( configuredManifest == null )
        {
            configuredManifest = newManifest;
        }
        else
        {
            JdkManifestFactory.merge( configuredManifest, newManifest, false );
        }
        savedConfiguredManifest = configuredManifest;
    }

    /**
     * The manifest file to use. This can be either the location of a manifest, or the name of a jar added through a
     * fileset. If its the name of an added jar, the task expects the manifest to be in the jar at META-INF/MANIFEST.MF.
     *
     * @param manifestFile the manifest file to use.
     *
     * @throws org.codehaus.plexus.archiver.ArchiverException
     */
    @SuppressWarnings(
    {
        "UnusedDeclaration"
    } )
    public void setManifest( File manifestFile )
        throws ArchiverException
    {
        if ( !manifestFile.exists() )
        {
            throw new ArchiverException( "Manifest file: " + manifestFile + " does not exist." );
        }

        this.manifestFile = manifestFile;
    }

    private Manifest getManifest( File manifestFile )
        throws ArchiverException
    {
        try ( InputStream in = fileInputStream( manifestFile ) )
        {
            return getManifest( in );
        }
        catch ( IOException e )
        {
            throw new ArchiverException( "Unable to read manifest file: " + manifestFile + " (" + e.getMessage() + ")",
                                         e );
        }
    }

    private Manifest getManifest( InputStream is )
        throws ArchiverException
    {
        try
        {
            return new Manifest( is );
        }
        catch ( IOException e )
        {
            throw new ArchiverException( "Unable to read manifest file" + " (" + e.getMessage() + ")", e );
        }
    }

    /**
     * Behavior when a Manifest is found in a zipfileset or zipgroupfileset file.
     * Valid values are "skip", "merge", and "mergewithoutmain".
     * "merge" will merge all of manifests together, and merge this into any
     * other specified manifests.
     * "mergewithoutmain" merges everything but the Main section of the manifests.
     * Default value is "skip".
     * <p>
     * Note: if this attribute's value is not "skip", the created jar will not
     * be readable by using java.util.jar.JarInputStream</p>
     *
     * @param config setting for found manifest behavior.
     */
    @SuppressWarnings(
    {
        "UnusedDeclaration"
    } )
    public void setFilesetmanifest( FilesetManifestConfig config )
    {
        filesetManifestConfig = config;
        mergeManifestsMain = FilesetManifestConfig.merge == config;

        if ( ( filesetManifestConfig != null ) && filesetManifestConfig != FilesetManifestConfig.skip )
        {

            doubleFilePass = true;
        }
    }

    /**
     * @param indexJar The indexjar
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    public void addConfiguredIndexJars( File indexJar )
    {
        if ( indexJars == null )
        {
            indexJars = new ArrayList<>();
        }
        indexJars.add( indexJar.getAbsolutePath() );
    }

    @Override
    protected void initZipOutputStream( ConcurrentJarCreator zOut )
        throws ArchiverException, IOException
    {
        if ( !skipWriting )
        {
            Manifest jarManifest = createManifest();
            writeManifest( zOut, jarManifest );
        }
    }

    @Override
    protected boolean hasVirtualFiles()
    {
        getLogger().debug( "\n\n\nChecking for jar manifest virtual files...\n\n\n" );
        System.out.flush();

        return ( configuredManifest != null ) || ( manifest != null ) || ( manifestFile != null )
                   || super.hasVirtualFiles();
    }

    /**
     * Creates the manifest to be added to the JAR archive.
     * Sub-classes may choose to override this method
     * in order to inspect or modify the JAR manifest file.
     *
     * @return the manifest for the JAR archive.
     *
     * @throws ArchiverException
     */
    protected Manifest createManifest()
        throws ArchiverException
    {
        Manifest finalManifest = Manifest.getDefaultManifest( minimalDefaultManifest );

        if ( ( manifest == null ) && ( manifestFile != null ) )
        {
            // if we haven't got the manifest yet, attempt to
            // get it now and have manifest be the final merge
            manifest = getManifest( manifestFile );
        }

        /*
         * Precedence: manifestFile wins over inline manifest,
         * over manifests read from the filesets over the original
         * manifest.
         *
         * merge with null argument is a no-op
         */
        if ( isInUpdateMode() )
        {
            JdkManifestFactory.merge( finalManifest, originalManifest, false );
        }
        JdkManifestFactory.merge( finalManifest, filesetManifest, false );
        JdkManifestFactory.merge( finalManifest, configuredManifest, false );
        JdkManifestFactory.merge( finalManifest, manifest, !mergeManifestsMain );

        return finalManifest;
    }

    private void writeManifest( ConcurrentJarCreator zOut, Manifest manifest )
        throws IOException, ArchiverException
    {
        for ( Enumeration<String> e = manifest.getWarnings(); e.hasMoreElements(); )
        {
            getLogger().warn( "Manifest warning: " + e.nextElement() );
        }

        zipDir( null, zOut, "META-INF/", DEFAULT_DIR_MODE, getEncoding() );

        // time to write the manifest
        ByteArrayOutputStream baos = new ByteArrayOutputStream( 128 );
        manifest.write( baos );
        InputStreamSupplier in = () -> new ByteArrayInputStream( baos.toByteArray() );

        super.zipFile( in, zOut, MANIFEST_NAME, System.currentTimeMillis(), null, DEFAULT_FILE_MODE, null,
                       false );
        super.initZipOutputStream( zOut );
    }

    @Override
    protected void finalizeZipOutputStream( ConcurrentJarCreator zOut )
        throws IOException, ArchiverException
    {
        if ( index )
        {
            createIndexList( zOut );
        }
    }

    /**
     * Create the index list to speed up classloading.
     * This is a JDK 1.3+ specific feature and is enabled by default. See
     * <a href="http://java.sun.com/j2se/1.3/docs/guide/jar/jar.html#JAR%20Index">
     * the JAR index specification</a> for more details.
     *
     * @param zOut the zip stream representing the jar being built.
     *
     * @throws IOException thrown if there is an error while creating the
     * index and adding it to the zip stream.
     * @throws org.codehaus.plexus.archiver.ArchiverException
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    private void createIndexList( ConcurrentJarCreator zOut )
        throws IOException, ArchiverException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream( 128 );
        // encoding must be UTF8 as specified in the specs.
        PrintWriter writer = new PrintWriter( new OutputStreamWriter( baos, StandardCharsets.UTF_8 ) );

        // version-info blankline
        writer.println( "JarIndex-Version: 1.0" );
        writer.println();

        // header newline
        writer.println( getDestFile().getName() );

        // filter out META-INF if it doesn't contain anything other than the index and manifest.
        // this is what sun.misc.JarIndex does, guess we ought to be consistent.
        Set<String> filteredDirs = addedDirs.allAddedDirs();
        // our added dirs always have a trailing slash
        if ( filteredDirs.contains( META_INF_NAME + '/' ) )
        {
            boolean add = false;
            for ( String entry : entries.keySet() )
            {
                if ( entry.startsWith( META_INF_NAME + '/' ) && !entry.equals( INDEX_NAME )
                         && !entry.equals( MANIFEST_NAME ) )
                {
                    add = true;
                    break;
                }
            }
            if ( !add )
            {
                filteredDirs.remove( META_INF_NAME + '/' );
            }
        }
        writeIndexLikeList( new ArrayList<>( filteredDirs ), rootEntries, writer );
        writer.println();

        if ( indexJars != null )
        {
            java.util.jar.Manifest mf = createManifest();
            String classpath = mf.getMainAttributes().getValue( ManifestConstants.ATTRIBUTE_CLASSPATH );
            String[] cpEntries = null;
            if ( classpath != null )
            {
                StringTokenizer tok = new StringTokenizer( classpath, " " );
                cpEntries = new String[ tok.countTokens() ];
                int c = 0;
                while ( tok.hasMoreTokens() )
                {
                    cpEntries[c++] = tok.nextToken();
                }
            }

            for ( String indexJar : indexJars )
            {
                String name = findJarName( indexJar, cpEntries );
                if ( name != null )
                {
                    List<String> dirs = new ArrayList<>();
                    List<String> files = new ArrayList<>();
                    grabFilesAndDirs( indexJar, dirs, files );
                    if ( dirs.size() + files.size() > 0 )
                    {
                        writer.println( name );
                        writeIndexLikeList( dirs, files, writer );
                        writer.println();
                    }
                }
            }
        }

        writer.flush();

        InputStreamSupplier in = () -> new ByteArrayInputStream( baos.toByteArray() );

        super.zipFile( in, zOut, INDEX_NAME, System.currentTimeMillis(), null,
                       DEFAULT_FILE_MODE, null, true );
    }

    /**
     * Overridden from Zip class to deal with manifests and index lists.
     */
    @Override
    protected void zipFile( InputStreamSupplier is, ConcurrentJarCreator zOut, String vPath,
                            long lastModified, File fromArchive,
                            int mode, String symlinkDestination, boolean addInParallel )
        throws IOException, ArchiverException
    {
        if ( MANIFEST_NAME.equalsIgnoreCase( vPath ) )
        {
            if ( !doubleFilePass || skipWriting )
            {
                try ( InputStream manifestInputStream = is.get() )
                {
                    filesetManifest( fromArchive, manifestInputStream );
                }
            }
        }
        else if ( INDEX_NAME.equalsIgnoreCase( vPath ) && index )
        {
            getLogger().warn( "Warning: selected " + archiveType + " files include a META-INF/INDEX.LIST which will"
                                  + " be replaced by a newly generated one." );
        }
        else
        {
            if ( index && !vPath.contains( "/" ) )
            {
                rootEntries.add( vPath );
            }
            super.zipFile( is, zOut, vPath, lastModified, fromArchive, mode, symlinkDestination, addInParallel );
        }
    }

    private void filesetManifest( File file, InputStream is )
        throws ArchiverException
    {
        if ( ( manifestFile != null ) && manifestFile.equals( file ) )
        {
            // If this is the same name specified in 'manifest', this
            // is the manifest to use
            getLogger().debug( "Found manifest " + file );
            if ( is != null )
            {
                manifest = getManifest( is );
            }
            else
            {
                manifest = getManifest( file );
            }
        }
        else if ( ( filesetManifestConfig != null ) && filesetManifestConfig != FilesetManifestConfig.skip )
        {
            // we add this to our group of fileset manifests
            getLogger().debug( "Found manifest to merge in file " + file );

            Manifest newManifest;
            if ( is != null )
            {
                newManifest = getManifest( is );
            }
            else
            {
                newManifest = getManifest( file );
            }

            if ( filesetManifest == null )
            {
                filesetManifest = newManifest;
            }
            else
            {
                JdkManifestFactory.merge( filesetManifest, newManifest, false );
            }
        }
    }

    @Override
    protected boolean createEmptyZip( File zipFile )
        throws ArchiverException
    {
        if ( !createEmpty )
        {
            return true;
        }

        try
        {
            getLogger().debug( "Building MANIFEST-only jar: " + getDestFile().getAbsolutePath() );
            zipArchiveOutputStream =
                new ZipArchiveOutputStream( bufferedOutputStream( fileOutputStream( getDestFile(), "jar" ) ) );

            zipArchiveOutputStream.setEncoding( getEncoding() );
            if ( isCompress() )
            {
                zipArchiveOutputStream.setMethod( ZipArchiveOutputStream.DEFLATED );
            }
            else
            {
                zipArchiveOutputStream.setMethod( ZipArchiveOutputStream.STORED );
            }
            ConcurrentJarCreator ps =
                new ConcurrentJarCreator( isRecompressAddedZips(), Runtime.getRuntime().availableProcessors() );
            initZipOutputStream( ps );
            finalizeZipOutputStream( ps );
        }
        catch ( IOException ioe )
        {
            throw new ArchiverException( "Could not create almost empty JAR archive (" + ioe.getMessage() + ")", ioe );
        }
        finally
        {
            // Close the output stream.
            //IOUtil.close( zOut );
            createEmpty = false;
        }
        return true;
    }

    /**
     * Make sure we don't think we already have a MANIFEST next time this task
     * gets executed.
     *
     * @see ZipArchiver#cleanUp
     */
    @Override
    protected void cleanUp()
        throws IOException
    {
        super.cleanUp();

        // we want to save this info if we are going to make another pass
        if ( !doubleFilePass || !skipWriting )
        {
            manifest = null;
            configuredManifest = savedConfiguredManifest;
            filesetManifest = null;
            originalManifest = null;
        }
        rootEntries.clear();
    }

    /**
     * reset to default values.
     *
     * @see ZipArchiver#reset
     */
    @Override
    public void reset()
    {
        super.reset();
        configuredManifest = null;
        filesetManifestConfig = null;
        mergeManifestsMain = false;
        manifestFile = null;
        index = false;
    }

    public enum FilesetManifestConfig
    {

        skip,
        merge,
        mergewithoutmain

    }

    /**
     * Writes the directory entries from the first and the filenames
     * from the second list to the given writer, one entry per line.
     *
     * @param dirs The directories
     * @param files The files
     * @param writer The printwriter ;)
     * @deprecated See <a href="https://bugs.openjdk.org/browse/JDK-8302819">JDK-8302819</a>
     */
    @Deprecated
    protected final void writeIndexLikeList( List<String> dirs, List<String> files, PrintWriter writer )
    {
        // JarIndex is sorting the directories by ascending order.
        // it has no value but cosmetic since it will be read into a
        // hashtable by the classloader, but we'll do so anyway.
        Collections.sort( dirs );
        Collections.sort( files );
        for ( String dir : dirs )
        {
            // try to be smart, not to be fooled by a weird directory name
            dir = dir.replace( '\\', '/' );
            if ( dir.startsWith( "./" ) )
            {
                dir = dir.substring( 2 );
            }
            while ( dir.startsWith( "/" ) )
            {
                dir = dir.substring( 1 );
            }
            int pos = dir.lastIndexOf( '/' );
            if ( pos != -1 )
            {
                dir = dir.substring( 0, pos );
            }

            // name newline
            writer.println( dir );
        }

        for ( String file : files )
        {
            writer.println( file );
        }
    }

    /**
     * try to guess the name of the given file.
     * <p>
     * If this jar has a classpath attribute in its manifest, we
     * can assume that it will only require an index of jars listed
     * there. try to find which classpath entry is most likely the
     * one the given file name points to.</p>
     * <p>
     * In the absence of a classpath attribute, assume the other
     * files will be placed inside the same directory as this jar and
     * use their basename.</p>
     * <p>
     * if there is a classpath and the given file doesn't match any
     * of its entries, return null.</p>
     *
     * @param fileName .
     * @param classpath .
     *
     * @return The guessed name
     */
    protected static String findJarName( String fileName, String[] classpath )
    {
        if ( classpath == null )
        {
            return new File( fileName ).getName();
        }
        fileName = fileName.replace( File.separatorChar, '/' );

        // longest match comes first
        SortedMap<String, String> matches = new TreeMap<>( Comparator.comparingInt( String::length ).reversed() );

        for ( String aClasspath : classpath )
        {
            if ( fileName.endsWith( aClasspath ) )
            {
                matches.put( aClasspath, aClasspath );
            }
            else
            {
                int slash = aClasspath.indexOf( "/" );
                String candidate = aClasspath;
                while ( slash > -1 )
                {
                    candidate = candidate.substring( slash + 1 );
                    if ( fileName.endsWith( candidate ) )
                    {
                        matches.put( candidate, aClasspath );
                        break;
                    }
                    slash = candidate.indexOf( "/" );
                }
            }
        }

        return matches.size() == 0 ? null : matches.get( matches.firstKey() );
    }

    /**
     * Grab lists of all root-level files and all directories
     * contained in the given archive.
     *
     * @param file .
     * @param files .
     * @param dirs .
     *
     * @throws java.io.IOException
     */
    private void grabFilesAndDirs( String file, List<String> dirs, List<String> files )
        throws IOException
    {
        File zipFile = new File( file );
        if ( !zipFile.exists() )
        {
            getLogger().error( "JarArchive skipping non-existing file: " + zipFile.getAbsolutePath() );
        }
        else if ( zipFile.isDirectory() )
        {
            getLogger().info( "JarArchiver skipping indexJar " + zipFile + " because it is not a jar" );
        }
        else
        {
            try ( ZipFile zf = new ZipFile( file, "utf-8" ) )
            {
                Enumeration<ZipArchiveEntry> entries = zf.getEntries();
                HashSet<String> dirSet = new HashSet<>();
                while ( entries.hasMoreElements() )
                {
                    ZipArchiveEntry ze = entries.nextElement();
                    String name = ze.getName();
                    // avoid index for manifest-only jars.
                    if ( !name.equals( META_INF_NAME ) && !name.equals( META_INF_NAME + '/' ) && !name.equals(
                        INDEX_NAME ) && !name.equals( MANIFEST_NAME ) )
                    {
                        if ( ze.isDirectory() )
                        {
                            dirSet.add( name );
                        }
                        else if ( !name.contains( "/" ) )
                        {
                            files.add( name );
                        }
                        else
                        {
                            // a file, not in the root
                            // since the jar may be one without directory
                            // entries, add the parent dir of this file as
                            // well.
                            dirSet.add( name.substring( 0, name.lastIndexOf( "/" ) + 1 ) );
                        }
                    }
                }
                dirs.addAll( dirSet );
            }
        }
    }

    /**
     * Override the behavior of the Zip Archiver to match the output of the JAR tool.
     *
     * @param zipEntry to set the last modified time
     * @param lastModifiedTime to set in the zip entry only if {@link #getLastModifiedTime()} returns null
     */
    @Override
    protected void setZipEntryTime( ZipArchiveEntry zipEntry, long lastModifiedTime )
    {
        if ( getLastModifiedTime() != null )
        {
            lastModifiedTime = getLastModifiedTime().toMillis();
        }

        // The JAR tool does not round up, so we keep that behavior here (JDK-8277755).
        zipEntry.setTime( lastModifiedTime );
    }

}
