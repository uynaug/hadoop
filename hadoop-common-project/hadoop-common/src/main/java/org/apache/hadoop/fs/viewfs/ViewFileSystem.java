/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs.viewfs;

import static org.apache.hadoop.fs.viewfs.Constants.PERMISSION_555;
import static org.apache.hadoop.fs.viewfs.Constants.CONFIG_VIEWFS_ENABLE_INNER_CACHE;
import static org.apache.hadoop.fs.viewfs.Constants.CONFIG_VIEWFS_ENABLE_INNER_CACHE_DEFAULT;
import static org.apache.hadoop.fs.viewfs.Constants.CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT;
import static org.apache.hadoop.fs.viewfs.Constants.CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT_DEFAULT;

import com.google.common.base.Function;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsConstants;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.AclUtil;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.QuotaUsage;
import org.apache.hadoop.fs.viewfs.InodeTree.INode;
import org.apache.hadoop.fs.viewfs.InodeTree.INodeLink;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Time;

/**
 * ViewFileSystem (extends the FileSystem interface) implements a client-side
 * mount table. Its spec and implementation is identical to {@link ViewFs}.
 */

@InterfaceAudience.Public
@InterfaceStability.Evolving /*Evolving for a release,to be changed to Stable */
public class ViewFileSystem extends FileSystem {

  private static final Path ROOT_PATH = new Path(Path.SEPARATOR);

  static AccessControlException readOnlyMountTable(final String operation,
      final String p) {
    return new AccessControlException( 
        "InternalDir of ViewFileSystem is readonly; operation=" + operation + 
        "Path=" + p);
  }
  static AccessControlException readOnlyMountTable(final String operation,
      final Path p) {
    return readOnlyMountTable(operation, p.toString());
  }

  /**
   * Caching children filesystems. HADOOP-15565.
   */
  static class InnerCache {
    private Map<Key, FileSystem> map = new HashMap<>();

    FileSystem get(URI uri, Configuration config) throws IOException {
      Key key = new Key(uri);
      if (map.get(key) == null) {
        FileSystem fs = FileSystem.newInstance(uri, config);
        map.put(key, fs);
        return fs;
      } else {
        return map.get(key);
      }
    }

    void closeAll() {
      for (FileSystem fs : map.values()) {
        try {
          fs.close();
        } catch (IOException e) {
          LOG.info("Fail closing ViewFileSystem's child filesystem " + fs, e);
        }
      }
    }

    InnerCache unmodifiableCache() {
      map = Collections.unmodifiableMap(map);
      return this;
    }

    /**
     * All the cached instances share the same UGI so there is no need to have a
     * URI in the Key. Make the Key simple with just the scheme and authority.
     */
    private static class Key {
      private final String scheme;
      private final String authority;

      Key(URI uri) {
        scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        authority =
            uri.getAuthority() == null ? "" : uri.getAuthority().toLowerCase();
      }

      @Override
      public int hashCode() {
        return Objects.hash(scheme, authority);
      }

      @Override
      public boolean equals(Object obj) {
        if (obj == this) {
          return true;
        }
        if (obj != null && obj instanceof Key) {
          Key that = (Key) obj;
          return this.scheme.equals(that.scheme) && this.authority
              .equals(that.authority);
        }
        return false;
      }
    }
  }

  static public class MountPoint {
    /**
     *  The source of the mount.
     */
    private Path src;

    /**
     * One or more targets of the mount.
     * Multiple targets imply MergeMount.
     */
    private String[] targets;

    MountPoint(Path srcPath, String[] targetURIs) {
      src = srcPath;
      targets = targetURIs;
    }
    Path getSrc() {
      return src;
    }

    URI[] getTargets() {
      URI[] targetUris = new URI[targets.length];
      for (int i = 0; i < targets.length; i++) {
        targetUris[i] = URI.create(targets[i]);
      }
      return targetUris;
    }

    public String[] getTargetFileSystemPaths() {
      return targets;
    }
  }
  
  final long creationTime; // of the the mount table
  final UserGroupInformation ugi; // the user/group of user who created mtable
  URI myUri;
  private Path workingDir;
  Configuration config;
  InodeTree<FileSystem> fsState;  // the fs state; ie the mount table
  Path homeDir = null;
  private boolean enableInnerCache = false;
  private InnerCache cache;
  // Default to rename within same mountpoint
  private RenameStrategy renameStrategy = RenameStrategy.SAME_MOUNTPOINT;
  /**
   * Make the path Absolute and get the path-part of a pathname.
   * Checks that URI matches this file system 
   * and that the path-part is a valid name.
   * 
   * @param p path
   * @return path-part of the Path p
   */
  private String getUriPath(final Path p) {
    checkPath(p);
    return makeAbsolute(p).toUri().getPath();
  }
  
  private Path makeAbsolute(final Path f) {
    return f.isAbsolute() ? f : new Path(workingDir, f);
  }
  
  /**
   * This is the  constructor with the signature needed by
   * {@link FileSystem#createFileSystem(URI, Configuration)}
   * 
   * After this constructor is called initialize() is called.
   * @throws IOException 
   */
  public ViewFileSystem() throws IOException {
    ugi = UserGroupInformation.getCurrentUser();
    creationTime = Time.now();
  }

  /**
   * Return the protocol scheme for the FileSystem.
   *
   * @return <code>viewfs</code>
   */
  @Override
  public String getScheme() {
    return FsConstants.VIEWFS_SCHEME;
  }

  /**
   * Called after a new FileSystem instance is constructed.
   * @param theUri a uri whose authority section names the host, port, etc. for
   *        this FileSystem
   * @param conf the configuration
   */
  @Override
  public void initialize(final URI theUri, final Configuration conf)
      throws IOException {
    super.initialize(theUri, conf);
    setConf(conf);
    config = conf;
    enableInnerCache = config.getBoolean(CONFIG_VIEWFS_ENABLE_INNER_CACHE,
        CONFIG_VIEWFS_ENABLE_INNER_CACHE_DEFAULT);
    cache = new InnerCache();
    // Now build  client side view (i.e. client side mount table) from config.
    final String authority = theUri.getAuthority();
    try {
      myUri = new URI(FsConstants.VIEWFS_SCHEME, authority, "/", null, null);
      fsState = new InodeTree<FileSystem>(conf, authority) {

        @Override
        protected Function<URI, FileSystem> initAndGetTargetFs() {
          return new Function<URI, FileSystem>() {
            @Override
            public FileSystem apply(final URI uri) {
              FileSystem fs;
              try {
                fs = ugi.doAs(new PrivilegedExceptionAction<FileSystem>() {
                  @Override
                  public FileSystem run() throws IOException {
                    if (enableInnerCache) {
                      synchronized (cache) {
                        return cache.get(uri, config);
                      }
                    } else {
                      return FileSystem.get(uri, config);
                    }
                  }
                });
                return new ChRootedFileSystem(fs, uri);
              } catch (IOException | InterruptedException ex) {
                LOG.error("Could not initialize the underlying FileSystem "
                    + "object. Exception: " + ex.toString());
              }
              return null;
            }
          };
        }

        @Override
        protected FileSystem getTargetFileSystem(final INodeDir<FileSystem> dir)
          throws URISyntaxException {
          return new InternalDirOfViewFs(dir, creationTime, ugi, myUri, config);
        }

        @Override
        protected FileSystem getTargetFileSystem(final String settings,
            final URI[] uris) throws URISyntaxException, IOException {
          return NflyFSystem.createFileSystem(uris, config, settings);
        }
      };

      workingDir = this.getHomeDirectory();
      renameStrategy = RenameStrategy.valueOf(
          conf.get(Constants.CONFIG_VIEWFS_RENAME_STRATEGY,
              RenameStrategy.SAME_MOUNTPOINT.toString()));
    } catch (URISyntaxException e) {
      throw new IOException("URISyntax exception: " + theUri);
    }

  }

  /**
   * Convenience Constructor for apps to call directly
   * @param theUri which must be that of ViewFileSystem
   * @param conf
   * @throws IOException
   */
  ViewFileSystem(final URI theUri, final Configuration conf)
      throws IOException {
    this();
    initialize(theUri, conf);
  }
  
  /**
   * Convenience Constructor for apps to call directly
   * @param conf
   * @throws IOException
   */
  public ViewFileSystem(final Configuration conf) throws IOException {
    this(FsConstants.VIEWFS_URI, conf);
  }
  
  public Path getTrashCanLocation(final Path f) throws IOException {
    final InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    return res.isInternalDir() ? null : res.targetFileSystem.getHomeDirectory();
  }
  
  @Override
  public URI getUri() {
    return myUri;
  }
  
  @Override
  public Path resolvePath(final Path f) throws IOException {
    final InodeTree.ResolveResult<FileSystem> res;
      res = fsState.resolve(getUriPath(f), true);
    if (res.isInternalDir()) {
      return f;
    }
    return res.targetFileSystem.resolvePath(res.remainingPath);
  }
  
  @Override
  public Path getHomeDirectory() {
    if (homeDir == null) {
      String base = fsState.getHomeDirPrefixValue();
      if (base == null) {
        base = "/user";
      }
      homeDir = (base.equals("/") ? 
          this.makeQualified(new Path(base + ugi.getShortUserName())):
          this.makeQualified(new Path(base + "/" + ugi.getShortUserName())));
    }
    return homeDir;
  }
  
  @Override
  public Path getWorkingDirectory() {
    return workingDir;
  }

  @Override
  public void setWorkingDirectory(final Path new_dir) {
    getUriPath(new_dir); // this validates the path
    workingDir = makeAbsolute(new_dir);
  }
  
  @Override
  public FSDataOutputStream append(final Path f, final int bufferSize,
      final Progressable progress) throws IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.append(res.remainingPath, bufferSize, progress);
  }
  
  @Override
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission,
      EnumSet<CreateFlag> flags, int bufferSize, short replication,
      long blockSize, Progressable progress) throws IOException {
    InodeTree.ResolveResult<FileSystem> res;
    try {
      res = fsState.resolve(getUriPath(f), false);
    } catch (FileNotFoundException e) {
        throw readOnlyMountTable("create", f);
    }
    assert(res.remainingPath != null);
    return res.targetFileSystem.createNonRecursive(res.remainingPath,
        permission, flags, bufferSize, replication, blockSize, progress);
  }
  
  @Override
  public FSDataOutputStream create(final Path f, final FsPermission permission,
      final boolean overwrite, final int bufferSize, final short replication,
      final long blockSize, final Progressable progress) throws IOException {
    InodeTree.ResolveResult<FileSystem> res;
    try {
      res = fsState.resolve(getUriPath(f), false);
    } catch (FileNotFoundException e) {
        throw readOnlyMountTable("create", f);
    }
    assert(res.remainingPath != null);
    return res.targetFileSystem.create(res.remainingPath, permission,
        overwrite, bufferSize, replication, blockSize, progress);
  }

  
  @Override
  public boolean delete(final Path f, final boolean recursive)
      throws AccessControlException, FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
        fsState.resolve(getUriPath(f), true);
    // If internal dir or target is a mount link (ie remainingPath is Slash)
    if (res.isInternalDir() || res.remainingPath == InodeTree.SlashPath) {
      throw readOnlyMountTable("delete", f);
    }
    return res.targetFileSystem.delete(res.remainingPath, recursive);
  }
  
  @Override
  @SuppressWarnings("deprecation")
  public boolean delete(final Path f)
      throws AccessControlException, FileNotFoundException, IOException {
    return delete(f, true);
  }
  
  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus fs, 
      long start, long len) throws IOException {
    final InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(fs.getPath()), true);
    return res.targetFileSystem.getFileBlockLocations(
        new ViewFsFileStatus(fs, res.remainingPath), start, len);
  }

  @Override
  public FileChecksum getFileChecksum(final Path f)
      throws AccessControlException, FileNotFoundException,
      IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.getFileChecksum(res.remainingPath);
  }

  private static FileStatus fixFileStatus(FileStatus orig,
      Path qualified) throws IOException {
    // FileStatus#getPath is a fully qualified path relative to the root of
    // target file system.
    // We need to change it to viewfs URI - relative to root of mount table.

    // The implementors of RawLocalFileSystem were trying to be very smart.
    // They implement FileStatus#getOwner lazily -- the object
    // returned is really a RawLocalFileSystem that expect the
    // FileStatus#getPath to be unchanged so that it can get owner when needed.
    // Hence we need to interpose a new ViewFileSystemFileStatus that
    // works around.
    if ("file".equals(orig.getPath().toUri().getScheme())) {
      orig = wrapLocalFileStatus(orig, qualified);
    }

    orig.setPath(qualified);
    return orig;
  }

  private static FileStatus wrapLocalFileStatus(FileStatus orig,
      Path qualified) {
    return orig instanceof LocatedFileStatus
        ? new ViewFsLocatedFileStatus((LocatedFileStatus)orig, qualified)
        : new ViewFsFileStatus(orig, qualified);
  }

  @Override
  public FileStatus getFileStatus(final Path f) throws AccessControlException,
      FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(f), true);
    FileStatus status =  res.targetFileSystem.getFileStatus(res.remainingPath);
    return fixFileStatus(status, this.makeQualified(f));
  }
  
  @Override
  public void access(Path path, FsAction mode) throws AccessControlException,
      FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    res.targetFileSystem.access(res.remainingPath, mode);
  }

  @Override
  public FileStatus[] listStatus(final Path f) throws AccessControlException,
      FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(f), true);
    
    FileStatus[] statusLst = res.targetFileSystem.listStatus(res.remainingPath);
    if (!res.isInternalDir()) {
      // We need to change the name in the FileStatus as described in
      // {@link #getFileStatus }
      int i = 0;
      for (FileStatus status : statusLst) {
          statusLst[i++] = fixFileStatus(status,
              getChrootedPath(res, status, f));
      }
    }
    return statusLst;
  }

  @Override
  public RemoteIterator<LocatedFileStatus>listLocatedStatus(final Path f,
      final PathFilter filter) throws FileNotFoundException, IOException {
    final InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(f), true);
    final RemoteIterator<LocatedFileStatus> statusIter =
        res.targetFileSystem.listLocatedStatus(res.remainingPath);

    if (res.isInternalDir()) {
      return statusIter;
    }

    return new RemoteIterator<LocatedFileStatus>() {
      @Override
      public boolean hasNext() throws IOException {
        return statusIter.hasNext();
      }

      @Override
      public LocatedFileStatus next() throws IOException {
        final LocatedFileStatus status = statusIter.next();
        return (LocatedFileStatus)fixFileStatus(status,
            getChrootedPath(res, status, f));
      }
    };
  }

  private Path getChrootedPath(InodeTree.ResolveResult<FileSystem> res,
      FileStatus status, Path f) throws IOException {
    final String suffix;
    if (res.targetFileSystem instanceof ChRootedFileSystem) {
      suffix = ((ChRootedFileSystem)res.targetFileSystem)
          .stripOutRoot(status.getPath());
    } else { // nfly
      suffix = ((NflyFSystem.NflyStatus)status).stripRoot();
    }
    return this.makeQualified(
        suffix.length() == 0 ? f : new Path(res.resolvedPath, suffix));
  }

  @Override
  public boolean mkdirs(Path dir) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(dir), false);
    return res.targetFileSystem.mkdirs(res.remainingPath);
  }

  @Override
  public boolean mkdirs(final Path dir, final FsPermission permission)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res = 
        fsState.resolve(getUriPath(dir), false);
    return res.targetFileSystem.mkdirs(res.remainingPath, permission);
  }

  @Override
  public FSDataInputStream open(final Path f, final int bufferSize)
      throws AccessControlException, FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
        fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.open(res.remainingPath, bufferSize);
  }

  
  @Override
  public boolean rename(final Path src, final Path dst) throws IOException {
    // passing resolveLastComponet as false to catch renaming a mount point to 
    // itself. We need to catch this as an internal operation and fail.
    InodeTree.ResolveResult<FileSystem> resSrc = 
      fsState.resolve(getUriPath(src), false); 
  
    if (resSrc.isInternalDir()) {
      throw readOnlyMountTable("rename", src);
    }
      
    InodeTree.ResolveResult<FileSystem> resDst = 
      fsState.resolve(getUriPath(dst), false);
    if (resDst.isInternalDir()) {
          throw readOnlyMountTable("rename", dst);
    }

    URI srcUri = resSrc.targetFileSystem.getUri();
    URI dstUri = resDst.targetFileSystem.getUri();

    verifyRenameStrategy(srcUri, dstUri,
        resSrc.targetFileSystem == resDst.targetFileSystem, renameStrategy);

    if (resSrc.targetFileSystem instanceof ChRootedFileSystem &&
        resDst.targetFileSystem instanceof ChRootedFileSystem) {
      ChRootedFileSystem srcFS = (ChRootedFileSystem) resSrc.targetFileSystem;
      ChRootedFileSystem dstFS = (ChRootedFileSystem) resDst.targetFileSystem;
      return srcFS.getMyFs().rename(srcFS.fullPath(resSrc.remainingPath),
          dstFS.fullPath(resDst.remainingPath));
    } else {
      return resSrc.targetFileSystem.rename(resSrc.remainingPath, resDst.remainingPath);
    }
  }

  static void verifyRenameStrategy(URI srcUri, URI dstUri,
      boolean isSrcDestSame, ViewFileSystem.RenameStrategy renameStrategy)
      throws IOException {
    switch (renameStrategy) {
    case SAME_FILESYSTEM_ACROSS_MOUNTPOINT:
      if (srcUri.getAuthority() != null) {
        if (!(srcUri.getScheme().equals(dstUri.getScheme()) && srcUri
            .getAuthority().equals(dstUri.getAuthority()))) {
          throw new IOException("Renames across Mount points not supported");
        }
      }

      break;
    case SAME_TARGET_URI_ACROSS_MOUNTPOINT:
      // Alternate 2: Rename across mountpoints with same target.
      // i.e. Rename across alias mountpoints.
      //
      // Note we compare the URIs. the URIs include the link targets.
      // hence we allow renames across mount links as long as the mount links
      // point to the same target.
      if (!srcUri.equals(dstUri)) {
        throw new IOException("Renames across Mount points not supported");
      }

      break;
    case SAME_MOUNTPOINT:
      //
      // Alternate 3 : renames ONLY within the the same mount links.
      //
      if (!isSrcDestSame) {
        throw new IOException("Renames across Mount points not supported");
      }
      break;
    default:
      throw new IllegalArgumentException ("Unexpected rename strategy");
    }
  }

  @Override
  public boolean truncate(final Path f, final long newLength)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.truncate(res.remainingPath, newLength);
  }
  
  @Override
  public void setOwner(final Path f, final String username,
      final String groupname) throws AccessControlException,
      FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    res.targetFileSystem.setOwner(res.remainingPath, username, groupname); 
  }

  @Override
  public void setPermission(final Path f, final FsPermission permission)
      throws AccessControlException, FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    res.targetFileSystem.setPermission(res.remainingPath, permission); 
  }

  @Override
  public boolean setReplication(final Path f, final short replication)
      throws AccessControlException, FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.setReplication(res.remainingPath, replication);
  }

  @Override
  public void setTimes(final Path f, final long mtime, final long atime)
      throws AccessControlException, FileNotFoundException, IOException {
    InodeTree.ResolveResult<FileSystem> res = 
      fsState.resolve(getUriPath(f), true);
    res.targetFileSystem.setTimes(res.remainingPath, mtime, atime); 
  }

  @Override
  public void modifyAclEntries(Path path, List<AclEntry> aclSpec)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    res.targetFileSystem.modifyAclEntries(res.remainingPath, aclSpec);
  }

  @Override
  public void removeAclEntries(Path path, List<AclEntry> aclSpec)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    res.targetFileSystem.removeAclEntries(res.remainingPath, aclSpec);
  }

  @Override
  public void removeDefaultAcl(Path path)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    res.targetFileSystem.removeDefaultAcl(res.remainingPath);
  }

  @Override
  public void removeAcl(Path path)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    res.targetFileSystem.removeAcl(res.remainingPath);
  }

  @Override
  public void setAcl(Path path, List<AclEntry> aclSpec) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    res.targetFileSystem.setAcl(res.remainingPath, aclSpec);
  }

  @Override
  public AclStatus getAclStatus(Path path) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    return res.targetFileSystem.getAclStatus(res.remainingPath);
  }

  @Override
  public void setXAttr(Path path, String name, byte[] value,
      EnumSet<XAttrSetFlag> flag) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(path), true);
    res.targetFileSystem.setXAttr(res.remainingPath, name, value, flag);
  }

  @Override
  public byte[] getXAttr(Path path, String name) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(path), true);
    return res.targetFileSystem.getXAttr(res.remainingPath, name);
  }

  @Override
  public Map<String, byte[]> getXAttrs(Path path) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(path), true);
    return res.targetFileSystem.getXAttrs(res.remainingPath);
  }

  @Override
  public Map<String, byte[]> getXAttrs(Path path, List<String> names)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(path), true);
    return res.targetFileSystem.getXAttrs(res.remainingPath, names);
  }

  @Override
  public List<String> listXAttrs(Path path) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(path), true);
    return res.targetFileSystem.listXAttrs(res.remainingPath);
  }

  @Override
  public void removeXAttr(Path path, String name) throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    res.targetFileSystem.removeXAttr(res.remainingPath, name);
  }

  @Override
  public void setVerifyChecksum(final boolean verifyChecksum) {
    // This is a file system level operations, however ViewFileSystem
    // points to many file systems. Noop for ViewFileSystem.
  }

  /**
   * Initialize the target filesystem for all mount points.
   * @param mountPoints The mount points
   * @return Mapping of mount point and the initialized target filesystems
   * @throws RuntimeException when the target file system cannot be initialized
   */
  private Map<String, FileSystem> initializeMountedFileSystems(
      List<InodeTree.MountPoint<FileSystem>> mountPoints) {
    FileSystem fs = null;
    Map<String, FileSystem> fsMap = new HashMap<>(mountPoints.size());
    for (InodeTree.MountPoint<FileSystem> mount : mountPoints) {
      try {
        fs = mount.target.getTargetFileSystem();
        fsMap.put(mount.src, fs);
      } catch (IOException ex) {
        String errMsg = "Not able to initialize FileSystem for mount path " +
            mount.src + " with exception " + ex;
        LOG.error(errMsg);
        throw new RuntimeException(errMsg, ex);
      }
    }
    return fsMap;
  }
  
  @Override
  public long getDefaultBlockSize() {
    throw new NotInMountpointException("getDefaultBlockSize");
  }

  @Override
  public short getDefaultReplication() {
    throw new NotInMountpointException("getDefaultReplication");
  }

  @Override
  public FsServerDefaults getServerDefaults() throws IOException {
    throw new NotInMountpointException("getServerDefaults");
  }

  @Override
  public long getDefaultBlockSize(Path f) {
    try {
      InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(f), true);
      return res.targetFileSystem.getDefaultBlockSize(res.remainingPath);
    } catch (FileNotFoundException e) {
      throw new NotInMountpointException(f, "getDefaultBlockSize"); 
    } catch (IOException e) {
      throw new RuntimeException("Not able to initialize fs in "
          + " getDefaultBlockSize for path " + f + " with exception", e);
    }
  }

  @Override
  public short getDefaultReplication(Path f) {
    try {
      InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(f), true);
      return res.targetFileSystem.getDefaultReplication(res.remainingPath);
    } catch (FileNotFoundException e) {
      throw new NotInMountpointException(f, "getDefaultReplication"); 
    } catch (IOException e) {
      throw new RuntimeException("Not able to initialize fs in "
          + " getDefaultReplication for path " + f + " with exception", e);
    }
  }

  @Override
  public FsServerDefaults getServerDefaults(Path f) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.getServerDefaults(res.remainingPath);    
  }

  @Override
  public ContentSummary getContentSummary(Path f) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
      fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.getContentSummary(res.remainingPath);
  }

  @Override
  public QuotaUsage getQuotaUsage(Path f) throws IOException {
    InodeTree.ResolveResult<FileSystem> res =
        fsState.resolve(getUriPath(f), true);
    return res.targetFileSystem.getQuotaUsage(res.remainingPath);
  }

  @Override
  public void setWriteChecksum(final boolean writeChecksum) {
    // This is a file system level operations, however ViewFileSystem
    // points to many file systems. Noop for ViewFileSystem.
  }

  @Override
  public FileSystem[] getChildFileSystems() {
    List<InodeTree.MountPoint<FileSystem>> mountPoints =
        fsState.getMountPoints();
    Map<String, FileSystem> fsMap = initializeMountedFileSystems(mountPoints);
    Set<FileSystem> children = new HashSet<FileSystem>();
    for (InodeTree.MountPoint<FileSystem> mountPoint : mountPoints) {
      FileSystem targetFs = fsMap.get(mountPoint.src);
      children.addAll(Arrays.asList(targetFs.getChildFileSystems()));
    }
    return children.toArray(new FileSystem[]{});
  }
  
  public MountPoint[] getMountPoints() {
    List<InodeTree.MountPoint<FileSystem>> mountPoints = 
                  fsState.getMountPoints();
    
    MountPoint[] result = new MountPoint[mountPoints.size()];
    for ( int i = 0; i < mountPoints.size(); ++i ) {
      result[i] = new MountPoint(new Path(mountPoints.get(i).src), 
                              mountPoints.get(i).target.targetDirLinkList);
    }
    return result;
  }

  @Override
  public Path createSnapshot(Path path, String snapshotName)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    return res.targetFileSystem.createSnapshot(res.remainingPath, snapshotName);
  }

  @Override
  public void renameSnapshot(Path path, String snapshotOldName,
      String snapshotNewName) throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    res.targetFileSystem.renameSnapshot(res.remainingPath, snapshotOldName,
        snapshotNewName);
  }

  @Override
  public void deleteSnapshot(Path path, String snapshotName)
      throws IOException {
    InodeTree.ResolveResult<FileSystem> res = fsState.resolve(getUriPath(path),
        true);
    res.targetFileSystem.deleteSnapshot(res.remainingPath, snapshotName);
  }

  /**
   * Get the trash root directory for current user when the path
   * specified is deleted.
   *
   * If FORCE_INSIDE_MOUNT_POINT flag is not set, return the default trash root
   * from targetFS.
   *
   * When FORCE_INSIDE_MOUNT_POINT is set to true,
   * <ol>
   *   <li>
   *     If the trash root for path p is in the same mount point as path p,
   *       and one of:
   *       <ol>
   *         <li>The mount point isn't at the top of the target fs.</li>
   *         <li>The resolved path of path is root (in fallback FS).</li>
   *         <li>The trash isn't in user's target fs home directory
   *            get the corresponding viewFS path for the trash root and return
   *            it.
   *         </li>
   *       </ol>
   *   </li>
   *   <li>
   *     else, return the trash root under the root of the mount point
   *     (/{mntpoint}/.Trash/{user}).
   *   </li>
   * </ol>
   *
   * These conditions handle several different important cases:
   * <ul>
   *   <li>File systems may need to have more local trash roots, such as
   *         encryption zones or snapshot roots.</li>
   *   <li>The fallback mount should use the user's home directory.</li>
   *   <li>Cloud storage systems should not use trash in an implicity defined
   *        home directory, per a container, unless it is the fallback fs.</li>
   * </ul>
   *
   * @param path the trash root of the path to be determined.
   * @return the trash root path.
   */
  @Override
  public Path getTrashRoot(Path path) {

    try {
      InodeTree.ResolveResult<FileSystem> res =
          fsState.resolve(getUriPath(path), true);
      Path targetFSTrashRoot =
          res.targetFileSystem.getTrashRoot(res.remainingPath);

      // Allow clients to use old behavior of delegating to target fs.
      if (!config.getBoolean(CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT,
          CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT_DEFAULT)) {
        return targetFSTrashRoot;
      }

      // The trash root path from the target fs
      String targetFSTrashRootPath = targetFSTrashRoot.toUri().getPath();
      // The mount point path in the target fs
      String mountTargetPath = res.targetFileSystem.getUri().getPath();
      if (!mountTargetPath.endsWith("/")) {
        mountTargetPath = mountTargetPath + "/";
      }

      Path targetFsUserHome = res.targetFileSystem.getHomeDirectory();
      if (targetFSTrashRootPath.startsWith(mountTargetPath) &&
          !(mountTargetPath.equals(ROOT_PATH.toString()) &&
              !res.resolvedPath.equals(ROOT_PATH.toString()) &&
              (targetFsUserHome != null && targetFSTrashRootPath.startsWith(
                  targetFsUserHome.toUri().getPath())))) {
        String relativeTrashRoot =
            targetFSTrashRootPath.substring(mountTargetPath.length());
        return makeQualified(new Path(res.resolvedPath, relativeTrashRoot));
      } else {
        // Return the trash root for the mount point.
        return makeQualified(new Path(res.resolvedPath,
            TRASH_PREFIX + "/" + ugi.getShortUserName()));
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new NotInMountpointException(path, "getTrashRoot");
    }
  }

  /**
   * Get all the trash roots for current user or all users.
   *
   * When FORCE_INSIDE_MOUNT_POINT is set to true, we also return trash roots
   * under the root of each mount point, with their viewFS paths.
   *
   * @param allUsers return trash roots for all users if true.
   * @return all Trash root directories.
   */
  @Override
  public Collection<FileStatus> getTrashRoots(boolean allUsers) {
    // A map from targetFSPath -> FileStatus.
    // FileStatus can be from targetFS or viewFS.
    HashMap<Path, FileStatus> trashRoots = new HashMap<>();
    for (FileSystem fs : getChildFileSystems()) {
      for (FileStatus trash : fs.getTrashRoots(allUsers)) {
        trashRoots.put(trash.getPath(), trash);
      }
    }

    // Return trashRoots if FORCE_INSIDE_MOUNT_POINT is disabled.
    if (!config.getBoolean(CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT,
        CONFIG_VIEWFS_TRASH_FORCE_INSIDE_MOUNT_POINT_DEFAULT)) {
      return trashRoots.values();
    }

    // Get trash roots in TRASH_PREFIX dir inside mount points and fallback FS.
    List<InodeTree.MountPoint<FileSystem>> mountPoints =
        fsState.getMountPoints();
    // If we have a fallback FS, add a mount point for it as <"", fallback FS>.
    // The source path of a mount point shall not end with '/', thus for
    // fallback fs, we set its mount point src as "".
    if (fsState.getRootFallbackLink() != null) {
      mountPoints.add(new InodeTree.MountPoint<>("",
          fsState.getRootFallbackLink()));
    }

    try {
      for (InodeTree.MountPoint<FileSystem> mountPoint : mountPoints) {

        Path trashRoot =
            makeQualified(new Path(mountPoint.src + "/" + TRASH_PREFIX));

        // Continue if trashRoot does not exist for this mount point
        if (!exists(trashRoot)) {
          continue;
        }

        FileSystem targetFS = mountPoint.target.getTargetFileSystem();
        if (!allUsers) {
          Path userTrashRoot = new Path(trashRoot, ugi.getShortUserName());
          if (exists(userTrashRoot)) {
            Path targetFSUserTrashRoot = targetFS.makeQualified(
                new Path(targetFS.getUri().getPath(),
                    TRASH_PREFIX + "/" + ugi.getShortUserName()));
            trashRoots.put(targetFSUserTrashRoot, getFileStatus(userTrashRoot));
          }
        } else {
          FileStatus[] mountPointTrashRoots = listStatus(trashRoot);
          for (FileStatus trash : mountPointTrashRoots) {
            // Remove the mountPoint and the leading '/' to get the
            // relative targetFsTrash path
            String targetFsTrash = trash.getPath().toUri().getPath()
                .substring(mountPoint.src.length() + 1);
            Path targetFsTrashPath = targetFS.makeQualified(
                new Path(targetFS.getUri().getPath(), targetFsTrash));
            trashRoots.put(targetFsTrashPath, trash);
          }
        }
      }
    } catch (IOException e) {
      LOG.warn("Exception in get all trash roots for mount points", e);
    }

    return trashRoots.values();
  }

  /**
   * An instance of this class represents an internal dir of the viewFs
   * that is internal dir of the mount table.
   * It is a read only mount tables and create, mkdir or delete operations
   * are not allowed.
   * If called on create or mkdir then this target is the parent of the
   * directory in which one is trying to create or mkdir; hence
   * in this case the path name passed in is the last component. 
   * Otherwise this target is the end point of the path and hence
   * the path name passed in is null. 
   */
  static class InternalDirOfViewFs extends FileSystem {
    final InodeTree.INodeDir<FileSystem>  theInternalDir;
    final long creationTime; // of the the mount table
    final UserGroupInformation ugi; // the user/group of user who created mtable
    final URI myUri;
    
    public InternalDirOfViewFs(final InodeTree.INodeDir<FileSystem> dir,
        final long cTime, final UserGroupInformation ugi, URI uri,
        Configuration config) throws URISyntaxException {
      myUri = uri;
      try {
        initialize(myUri, config);
      } catch (IOException e) {
        throw new RuntimeException("Cannot occur");
      }
      theInternalDir = dir;
      creationTime = cTime;
      this.ugi = ugi;
    }

    static private void checkPathIsSlash(final Path f) throws IOException {
      if (f != InodeTree.SlashPath) {
        throw new IOException(
            "Internal implementation error: expected file name to be /");
      }
    }
    
    @Override
    public URI getUri() {
      return myUri;
    }

    @Override
    public Path getWorkingDirectory() {
      throw new RuntimeException(
          "Internal impl error: getWorkingDir should not have been called");
    }

    @Override
    public void setWorkingDirectory(final Path new_dir) {
      throw new RuntimeException(
          "Internal impl error: getWorkingDir should not have been called");
    }

    @Override
    public FSDataOutputStream append(final Path f, final int bufferSize,
        final Progressable progress) throws IOException {
      throw readOnlyMountTable("append", f);
    }

    @Override
    public FSDataOutputStream create(final Path f,
        final FsPermission permission, final boolean overwrite,
        final int bufferSize, final short replication, final long blockSize,
        final Progressable progress) throws AccessControlException {
      throw readOnlyMountTable("create", f);
    }

    @Override
    public boolean delete(final Path f, final boolean recursive)
        throws AccessControlException, IOException {
      checkPathIsSlash(f);
      throw readOnlyMountTable("delete", f);
    }
    
    @Override
    @SuppressWarnings("deprecation")
    public boolean delete(final Path f)
        throws AccessControlException, IOException {
      return delete(f, true);
    }

    @Override
    public BlockLocation[] getFileBlockLocations(final FileStatus fs,
        final long start, final long len) throws
        FileNotFoundException, IOException {
      checkPathIsSlash(fs.getPath());
      throw new FileNotFoundException("Path points to dir not a file");
    }

    @Override
    public FileChecksum getFileChecksum(final Path f)
        throws FileNotFoundException, IOException {
      checkPathIsSlash(f);
      throw new FileNotFoundException("Path points to dir not a file");
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
      checkPathIsSlash(f);
      return new FileStatus(0, true, 0, 0, creationTime, creationTime,
          PERMISSION_555, ugi.getShortUserName(), ugi.getPrimaryGroupName(),

          new Path(theInternalDir.fullPath).makeQualified(
              myUri, ROOT_PATH));
    }
    

    @Override
    public FileStatus[] listStatus(Path f) throws AccessControlException,
        FileNotFoundException, IOException {
      checkPathIsSlash(f);
      FileStatus[] result = new FileStatus[theInternalDir.getChildren().size()];
      int i = 0;
      for (Entry<String, INode<FileSystem>> iEntry :
          theInternalDir.getChildren().entrySet()) {
        INode<FileSystem> inode = iEntry.getValue();
        if (inode.isLink()) {
          INodeLink<FileSystem> link = (INodeLink<FileSystem>) inode;

          result[i++] = new FileStatus(0, false, 0, 0,
            creationTime, creationTime, PERMISSION_555,
            ugi.getShortUserName(), ugi.getPrimaryGroupName(),
            link.getTargetLink(),
            new Path(inode.fullPath).makeQualified(
                myUri, null));
        } else {
          result[i++] = new FileStatus(0, true, 0, 0,
            creationTime, creationTime, PERMISSION_555,
            ugi.getShortUserName(), ugi.getPrimaryGroupName(),
            new Path(inode.fullPath).makeQualified(
                myUri, null));
        }
      }
      return result;
    }

    @Override
    public boolean mkdirs(Path dir, FsPermission permission)
        throws AccessControlException, FileAlreadyExistsException {
      if (theInternalDir.isRoot() && dir == null) {
        throw new FileAlreadyExistsException("/ already exits");
      }
      // Note dir starts with /
      if (theInternalDir.getChildren().containsKey(
          dir.toString().substring(1))) {
        return true; // this is the stupid semantics of FileSystem
      }
      throw readOnlyMountTable("mkdirs",  dir);
    }

    @Override
    public boolean mkdirs(Path dir)
        throws AccessControlException, FileAlreadyExistsException {
      return mkdirs(dir, null);
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize)
        throws AccessControlException, FileNotFoundException, IOException {
      checkPathIsSlash(f);
      throw new FileNotFoundException("Path points to dir not a file");
    }

    @Override
    public boolean rename(Path src, Path dst) throws AccessControlException,
        IOException {
      checkPathIsSlash(src);
      checkPathIsSlash(dst);
      throw readOnlyMountTable("rename", src);     
    }

    @Override
    public boolean truncate(Path f, long newLength) throws IOException {
      throw readOnlyMountTable("truncate", f);
    }

    @Override
    public void setOwner(Path f, String username, String groupname)
        throws AccessControlException, IOException {
      checkPathIsSlash(f);
      throw readOnlyMountTable("setOwner", f);
    }

    @Override
    public void setPermission(Path f, FsPermission permission)
        throws AccessControlException, IOException {
      checkPathIsSlash(f);
      throw readOnlyMountTable("setPermission", f);    
    }

    @Override
    public boolean setReplication(Path f, short replication)
        throws AccessControlException, IOException {
      checkPathIsSlash(f);
      throw readOnlyMountTable("setReplication", f);
    }

    @Override
    public void setTimes(Path f, long mtime, long atime)
        throws AccessControlException, IOException {
      checkPathIsSlash(f);
      throw readOnlyMountTable("setTimes", f);    
    }

    @Override
    public void setVerifyChecksum(boolean verifyChecksum) {
      // Noop for viewfs
    }

    @Override
    public FsServerDefaults getServerDefaults(Path f) throws IOException {
      throw new NotInMountpointException(f, "getServerDefaults");
    }
    
    @Override
    public long getDefaultBlockSize(Path f) {
      throw new NotInMountpointException(f, "getDefaultBlockSize");
    }

    @Override
    public short getDefaultReplication(Path f) {
      throw new NotInMountpointException(f, "getDefaultReplication");
    }

    @Override
    public void modifyAclEntries(Path path, List<AclEntry> aclSpec)
        throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("modifyAclEntries", path);
    }

    @Override
    public void removeAclEntries(Path path, List<AclEntry> aclSpec)
        throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("removeAclEntries", path);
    }

    @Override
    public void removeDefaultAcl(Path path) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("removeDefaultAcl", path);
    }

    @Override
    public void removeAcl(Path path) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("removeAcl", path);
    }

    @Override
    public void setAcl(Path path, List<AclEntry> aclSpec) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("setAcl", path);
    }

    @Override
    public AclStatus getAclStatus(Path path) throws IOException {
      checkPathIsSlash(path);
      return new AclStatus.Builder().owner(ugi.getShortUserName())
          .group(ugi.getPrimaryGroupName())
          .addEntries(AclUtil.getMinimalAcl(PERMISSION_555))
          .stickyBit(false).build();
    }

    @Override
    public void setXAttr(Path path, String name, byte[] value,
        EnumSet<XAttrSetFlag> flag) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("setXAttr", path);
    }

    @Override
    public byte[] getXAttr(Path path, String name) throws IOException {
      throw new NotInMountpointException(path, "getXAttr");
    }

    @Override
    public Map<String, byte[]> getXAttrs(Path path) throws IOException {
      throw new NotInMountpointException(path, "getXAttrs");
    }

    @Override
    public Map<String, byte[]> getXAttrs(Path path, List<String> names)
        throws IOException {
      throw new NotInMountpointException(path, "getXAttrs");
    }

    @Override
    public List<String> listXAttrs(Path path) throws IOException {
      throw new NotInMountpointException(path, "listXAttrs");
    }

    @Override
    public void removeXAttr(Path path, String name) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("removeXAttr", path);
    }

    @Override
    public Path createSnapshot(Path path, String snapshotName)
        throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("createSnapshot", path);
    }

    @Override
    public void renameSnapshot(Path path, String snapshotOldName,
        String snapshotNewName) throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("renameSnapshot", path);
    }

    @Override
    public void deleteSnapshot(Path path, String snapshotName)
        throws IOException {
      checkPathIsSlash(path);
      throw readOnlyMountTable("deleteSnapshot", path);
    }

    @Override
    public QuotaUsage getQuotaUsage(Path f) throws IOException {
      throw new NotInMountpointException(f, "getQuotaUsage");
    }
  }

  enum RenameStrategy {
    SAME_MOUNTPOINT, SAME_TARGET_URI_ACROSS_MOUNTPOINT,
    SAME_FILESYSTEM_ACROSS_MOUNTPOINT
  }

  @Override
  public void close() throws IOException {
    super.close();
    if (enableInnerCache && cache != null) {
      cache.closeAll();
    }
  }
}
