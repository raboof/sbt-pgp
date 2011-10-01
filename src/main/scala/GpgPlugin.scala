package com.github.jsuereth.gpg

import sbt._
import Keys._
import sbt.Project.Initialize
import complete.Parser
import complete.DefaultParsers._

/** The interface used to sign plugins. */
trait GpgSigner {
  def sign(file: File, signatureFile: File): File
  def generateKey(pubKey: File, secKey: File, identity: String): Unit
}

/** A GpgSigner that uses the command-line to run gpg. */
class CommandLineGpgSigner(command: String) extends GpgSigner {
  def sign(file: File, signatureFile: File): File = {
      if (signatureFile.exists) IO.delete(signatureFile)
       // --output = sig file
       Process(command, Seq("--detach-sign", "--output", signatureFile.getAbsolutePath, file.getAbsolutePath)).!
       signatureFile 
  }
  def generateKey(pubKey: File, secKey: File, identity: String): Unit = error("Command line generateKey not implemented")
}
/** A GpgSigner that uses bouncy castle. */
class BouncyCastleGpgSigner(secretKeyRingFile: File, passPhrase: Array[Char]) extends GpgSigner {
  lazy val secring = BouncyCastle.loadSecretKeyRing(secretKeyRingFile)
  def sign(file: File, signatureFile: File): File = {
    if (signatureFile.exists) IO.delete(signatureFile)
    if (!signatureFile.getParentFile.exists) IO.createDirectory(signatureFile.getParentFile)
    secring.secretKey.sign(file, signatureFile, passPhrase)
  }
  def generateKey(pubKey: File, secKey: File, identity: String): Unit =
    BouncyCastle.makeKeys(identity, passPhrase, pubKey, secKey)
}

object GpgPlugin extends Plugin {
  val gpgCommand = SettingKey[String]("gpg-command", "The path of the GPG command to run")
  val gpgRunner = TaskKey[GpgSigner]("gpg-runner", "The helper class to run GPG commands.")  
  val gpgSecretRing = SettingKey[File]("gpg-secret-ring", "The location of the secret key ring.  Only needed if using bouncy castle.")
  val gpgPublicRing = SettingKey[File]("gpg-public-ring", "The location of the secret key ring.  Only needed if using bouncy castle.")
  val gpgPassphrase = SettingKey[Option[Array[Char]]]("gpg-passphrase", "The passphrase associated with the secret used to sign artifacts.")
  val gpgGenKey = InputKey[Unit]("gpg-gen-key", "Creates a new PGP key using bouncy castle.   Must provide <name> <email>.  The passphrase setting must be set for this to work.")

  // TODO - home dir, use-agent, 
  // TODO - --batch and pasphrase and read encrypted passphrase...
  // TODO --local-user
  // TODO --detach-sign
  // TODO --armor
  // TODO --no-tty
  // TODO  Signature extension
  private[this] val gpgExtension = ".asc"
  
  override val settings = Seq(
    SettingKey[Boolean]("skip") in gpgRunner := false,
    gpgCommand := (if(isWindows) "gpg.exe" else "gpg"),
    gpgPassphrase := None,
    gpgPublicRing := file(System.getProperty("user.home")) / ".gnupg" / "pubring.gpg",
    gpgSecretRing := file(System.getProperty("user.home")) / ".gnupg" / "secring.gpg",
    // If the user isn't using GPG, we'll use a bouncy-castle ring.
    gpgPublicRing <<= gpgPublicRing apply {
      case f if f.exists => f
      case _ => file(System.getProperty("user.home")) / ".sbt" / "gpg" / "pubring.asc"
    },
    gpgSecretRing <<= gpgSecretRing apply {
      case f if f.exists => f
      case _ => file(System.getProperty("user.home")) / ".sbt" / "gpg" / "secring.asc"
    },
    // TODO - Select the runner based on the existence of the gpg/gpg.exe command rather than the configuring of a passPhrase.
    gpgRunner <<= (gpgSecretRing, gpgPassphrase, gpgCommand) map { (secring, optPass, command) =>
      // TODO - Catch errors and report issues.
      (optPass map (p => new BouncyCastleGpgSigner(secring, p))
       getOrElse new CommandLineGpgSigner(command))
    },
    packagedArtifacts <<= (packagedArtifacts, gpgRunner, SettingKey[Boolean]("skip") in gpgRunner, streams) map {
      (artifacts, r, skipZ, s) =>
        if (!skipZ) {
          artifacts flatMap {
            case (art, file) =>
              Seq(art                                                -> file, 
                  art.copy(extension = art.extension + gpgExtension) -> r.sign(file, new File(file.getAbsolutePath + gpgExtension)))
          }
        } else artifacts
    },
    gpgGenKey <<= InputTask(keyGenParser)(keyGenTask)
  )
  private[this] def keyGenParser: State => Parser[(String,String)] = {
      (state: State) =>
        val Email: Parser[String] =  (NotSpace ~ '@' ~ NotSpace ~ '.' ~ NotSpace) map { 
          case name ~ at ~ address ~ dot ~ subdomain => 
            new StringBuilder(name).append(at).append(address).append(dot).append(subdomain).toString
        }
        val name: Parser[String] = (any*) map (_ mkString "")
        (Space ~> Email ~ (Space ~> name))
  }
  private[this] def keyGenTask = { (parsed: TaskKey[(String,String)]) => 
    (parsed, gpgPublicRing, gpgSecretRing, gpgRunner, streams) map { (input, pub, sec, runner, s) =>
      if(pub.exists)  error("Public key ring (" + pub.getAbsolutePath + ") already exists!")
      if(sec.exists)  error("Secret key ring (" + sec.getAbsolutePath + ") already exists!")
      val (email, name) = input
      val identity = "%s <%s>".format(name, email)
      runner.generateKey(pub, sec, identity)
    }
  }
  // Helper to figure out how to run GPG signing...
  def isWindows = System.getProperty("os.name").toLowerCase().indexOf("windows") != -1
}