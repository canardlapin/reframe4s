import java.io.File

object ReframeBuildRoot {
  private val Marker = "project/artifact-graph.tsv"

  def resolve(overrideProperty: String): File =
    sys.props
      .get(overrideProperty)
      .map(path => new File(path).getCanonicalFile)
      .getOrElse {
        val codeSource =
          new File(
            getClass.getProtectionDomain.getCodeSource.getLocation.toURI
          )
        findFrom(codeSource).getOrElse {
          sys.error(
            s"cannot locate reframe4s build root from $codeSource; " +
              s"set -D$overrideProperty=/path/to/reframe4s"
          )
        }
      }

  private def findFrom(start: File): Option[File] = {
    var current =
      if (start.isDirectory) start.getCanonicalFile
      else start.getCanonicalFile.getParentFile
    while (current != null) {
      if (new File(current, Marker).isFile) return Some(current)
      current = current.getParentFile
    }
    None
  }
}
