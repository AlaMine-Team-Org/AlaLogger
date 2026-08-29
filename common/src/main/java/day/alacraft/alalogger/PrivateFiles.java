package day.alacraft.alalogger;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Keeps a file readable only by the account that owns it.
 *
 * <p>Two files here hold credentials: the config, which carries {@code
 * apiToken}, and the upload history, which carries a delete token for every log
 * this server has published. Both live on machines where "everyone with an
 * account" is a much larger group than the person who created them — a shared
 * game host, or a family PC.
 *
 * <p>POSIX is the easy half: {@code 0600} and done. Windows was left out of the
 * first release on the grounds that inheriting the config directory's ACL was
 * the honest limit, and that turned out to be wrong in the way that matters —
 * an inherited ACL on this machine named six principals, only one of which was
 * the owner. {@link AclFileAttributeView#setAcl} replaces the whole discretionary
 * list, inherited entries included, so a single owner-only entry is all it takes.
 *
 * <p>Best effort by design. A network share may have no usable view, and a
 * hardened host may refuse the change; a mod that will not start because it
 * could not tighten its own config would be a worse outcome than a file another
 * local account can read. Callers write the file first and call this second, so
 * a refusal costs the tightening, never the data.
 */
public final class PrivateFiles {

    private PrivateFiles() {
    }

    /**
     * Restricts {@code file} to its owner, as far as the filesystem allows.
     *
     * <p>Call this on a file that already exists. It is safe to call on one that
     * is still empty, which is what the history does: the permissions are in
     * place before the tokens are.
     */
    public static void restrictToOwner(Path file) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                return;
            }

            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                return;
            }

            UserPrincipal owner = Files.getOwner(file);
            // Full permissions rather than read+write: the owner has to be able
            // to replace and delete the file, and the history does exactly that
            // on every save.
            view.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            AlaLogger.LOGGER.debug("Could not restrict {} to its owner ({}).", file, e.getMessage());
        }
    }
}
