package io.openledger

class TagDistribution(prefix: String, tagCount: Int) {

  val tags: Seq[String] = Vector.tabulate(tagCount)(i => s"$prefix$i")

  def assignTag(entityId: String): String = tags(Math.abs(entityId.hashCode()) % tags.size)
}

object TagDistribution {
  def apply(prefix: String, tagCount: Int): TagDistribution = new TagDistribution(prefix, tagCount)
}