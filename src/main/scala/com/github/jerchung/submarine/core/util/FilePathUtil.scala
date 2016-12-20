package com.github.jerchung.submarine.core.util

import java.io.File

object FilePathUtil {
  def pathJoin(paths: String*): String = {
    paths.mkString(File.separator)
  }
}
