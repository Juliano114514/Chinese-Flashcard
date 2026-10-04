package com.example.chinese_flashcard.core.domain

import kotlin.math.hypot

/** A beginner trajectory check in normalised coordinates, not a handwriting grade. */
object StrokeMatcher {
  private fun distance(a: StrokePoint, b: StrokePoint) = hypot(a.x - b.x, a.y - b.y)
  private fun length(points: List<StrokePoint>) = points.zipWithNext()
    .sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()

  /** Both the input and the reference use the same 0..1 canvas coordinates. */
  fun feedback(input: List<StrokePoint>, expected: List<StrokePoint>): String? {
    if (input.size !in 2..1024 || input.any { !it.x.isFinite() || !it.y.isFinite() })
      return "Draw one complete stroke before lifting your finger."
    if (expected.size < 2 || expected.any { !it.x.isFinite() || !it.y.isFinite() })
      return "The stroke guide is unavailable. Please try again."
    val referenceLength = length(expected)
    if (referenceLength < .00001f) return "The stroke guide is unavailable. Please try again."
    val actualLength = length(input)
    if (actualLength < .008f) return "The stroke is too short. Follow the start and end points."
    val dx = expected.last().x - expected.first().x
    val dy = expected.last().y - expected.first().y
    val ax = input.last().x - input.first().x
    val ay = input.last().y - input.first().y
    if (hypot(dx, dy) > .015f && dx * ax + dy * ay < -.0001f)
      return "The stroke runs in the opposite direction. Follow the start-to-end order."
    if (distance(input.first(), expected.first()) > .16f)
      return "Start closer to the beginning of this stroke."
    if (distance(input.last(), expected.last()) > .18f)
      return "Finish closer to the end of this stroke."
    if (actualLength < referenceLength * .4f || actualLength > referenceLength * 2.2f)
      return "Check the stroke's length and turns; draw it once without retracing."
    val distances = resample(input, 32).zip(resample(expected, 32))
      .map { (a, b) -> distance(a, b) }
    if (distances.average() > .09 || distances.max() > .23f)
      return "The path moves too far from this stroke. Check its position and turns."
    return null
  }

  fun resample(points: List<StrokePoint>, count: Int): List<StrokePoint> {
    require(count >= 2 && points.isNotEmpty())
    require(points.all { it.x.isFinite() && it.y.isFinite() })
    val cumulative = mutableListOf(0f)
    points.zipWithNext().forEach { (a, b) -> cumulative.add(cumulative.last() + distance(a, b)) }
    val total = cumulative.last()
    if (total < .00001f) return List(count) { points.first() }
    var segment = 1
    return List(count) { index ->
      val target = total * index / (count - 1)
      while (segment < cumulative.lastIndex && cumulative[segment] < target) segment++
      val start = points[segment - 1]
      val end = points[segment]
      val span = cumulative[segment] - cumulative[segment - 1]
      val fraction = if (span == 0f) 0f else (target - cumulative[segment - 1]) / span
      StrokePoint(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
    }
  }
}
