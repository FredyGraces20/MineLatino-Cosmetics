import com.minelatino.cosmetics.client.AvatarPackage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local validation helper: java ... AvatarPackageProbe character.zip */
public final class AvatarPackageProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: AvatarPackageProbe <avatar.zip>");
        AvatarPackage avatar=AvatarPackage.parse(Files.readAllBytes(Path.of(args[0])));
        System.out.println("OK bones="+avatar.model().boneNames().size()+" animations="+avatar.animations().names().size()+" emotes="+avatar.emotes().size());
    }
}
