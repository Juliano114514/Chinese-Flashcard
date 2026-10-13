package com.example.chinese_flashcard.core.data

import com.example.chinese_flashcard.core.domain.UiMessageKey

/** Read-only compatibility for feedback text stored by older releases; never rewrites a draft. */
internal fun String?.writingFeedbackKey(): UiMessageKey? {
  if (this == null) return null
  return when (this) {
    UiMessageKey.CHARACTER_COMPLETED_WRITE_THE_NEXT_CHARACTER.name, "Character completed. Write the next character." -> UiMessageKey.CHARACTER_COMPLETED_WRITE_THE_NEXT_CHARACTER
    UiMessageKey.CHECK_THE_STROKE_S_LENGTH_AND_TURNS_DRAW_IT_ONCE_WITHOUT_RETRACING.name, "Check the stroke's length and turns; draw it once without retracing." -> UiMessageKey.CHECK_THE_STROKE_S_LENGTH_AND_TURNS_DRAW_IT_ONCE_WITHOUT_RETRACING
    UiMessageKey.DRAW_ONE_COMPLETE_STROKE_BEFORE_LIFTING_YOUR_FINGER.name, "Draw one complete stroke before lifting your finger." -> UiMessageKey.DRAW_ONE_COMPLETE_STROKE_BEFORE_LIFTING_YOUR_FINGER
    UiMessageKey.FINISH_CLOSER_TO_THE_END_OF_THIS_STROKE.name, "Finish closer to the end of this stroke." -> UiMessageKey.FINISH_CLOSER_TO_THE_END_OF_THIS_STROKE
    UiMessageKey.LAST_ACCEPTED_STROKE_REMOVED.name, "Last accepted stroke removed." -> UiMessageKey.LAST_ACCEPTED_STROKE_REMOVED
    UiMessageKey.START_CLOSER_TO_THE_BEGINNING_OF_THIS_STROKE.name, "Start closer to the beginning of this stroke." -> UiMessageKey.START_CLOSER_TO_THE_BEGINNING_OF_THIS_STROKE
    UiMessageKey.STROKE_ACCEPTED.name, "Stroke accepted." -> UiMessageKey.STROKE_ACCEPTED
    UiMessageKey.STROKE_DATA_IS_UNAVAILABLE_YOU_CAN_SKIP_THIS_WRITING_SESSION.name, "Stroke data is unavailable. You can skip this writing session." -> UiMessageKey.STROKE_DATA_IS_UNAVAILABLE_YOU_CAN_SKIP_THIS_WRITING_SESSION
    UiMessageKey.THE_PATH_MOVES_TOO_FAR_FROM_THIS_STROKE_CHECK_ITS_POSITION_AND_TURNS.name, "The path moves too far from this stroke. Check its position and turns." -> UiMessageKey.THE_PATH_MOVES_TOO_FAR_FROM_THIS_STROKE_CHECK_ITS_POSITION_AND_TURNS
    UiMessageKey.THE_STROKE_GUIDE_IS_UNAVAILABLE_PLEASE_TRY_AGAIN.name, "The stroke guide is unavailable. Please try again." -> UiMessageKey.THE_STROKE_GUIDE_IS_UNAVAILABLE_PLEASE_TRY_AGAIN
    UiMessageKey.THE_STROKE_IS_TOO_SHORT_FOLLOW_THE_START_AND_END_POINTS.name, "The stroke is too short. Follow the start and end points." -> UiMessageKey.THE_STROKE_IS_TOO_SHORT_FOLLOW_THE_START_AND_END_POINTS
    UiMessageKey.THE_STROKE_RUNS_IN_THE_OPPOSITE_DIRECTION_FOLLOW_THE_START_TO_END_ORDER.name, "The stroke runs in the opposite direction. Follow the start-to-end order." -> UiMessageKey.THE_STROKE_RUNS_IN_THE_OPPOSITE_DIRECTION_FOLLOW_THE_START_TO_END_ORDER
    UiMessageKey.WORD_COMPLETED_WRITE_THE_NEXT_WORD.name, "Word completed. Write the next word." -> UiMessageKey.WORD_COMPLETED_WRITE_THE_NEXT_WORD
    UiMessageKey.WRITING_COMPLETED.name, "Writing completed." -> UiMessageKey.WRITING_COMPLETED
    else -> null
  }
}
