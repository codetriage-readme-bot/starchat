package com.getjenny.starchat.entities

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 03/04/17.
  */

object TokenizersDescription {
  val analyzers_map: Map[String, (String, String)] = Map[String, (String, String)](
    "base" -> ("jenny_base_analyzer", "lowercase"),
    "stop" -> ("jenny_stop_analyzer", "lowercase + stopwords elimination"),
    "base_stem" -> ("jenny_base_stem_analyzer", "lowercase + stemming"),
    "stop_stem" -> ("jenny_stem_analyzer", "lowercase + stopwords elimination + stemming"),
    "shingles2" -> ("jenny_shingles_2_analyzer", "2-grams"),
    "shingles3" -> ("jenny_shingles_3_analyzer", "3-grams"),
    "shingles4" -> ("jenny_shingles_4_analyzer", "4-grams"),
    "shingles2_10" -> ("jenny_shingles_2_10_analyzer", "from 2 to 10 n-grams")
  )
}

case class TokenizerQueryRequest(
  tokenizer: String,
  text: String
)

case class TokenizerResponseItem(
  start_offset: Int,
  position: Int,
  end_offset: Int,
  token: String,
  token_type: String
)

case class TokenizerResponse(
  tokens: List[TokenizerResponseItem]
)
