package lila.api

import scalatags.Text.all.*

import lila.analyse.{ Analysis, AnalysisRepo }
import lila.game.GameRepo
import lila.memo.CacheApi
import chess.format.pgn.{ Pgn, PgnStr }
import lila.common.config.NetDomain

final class TextLpvExpand(
    gameRepo: GameRepo,
    analysisRepo: AnalysisRepo,
    studyApi: lila.study.StudyApi,
    pgnDump: PgnDump,
    studyPgnDump: lila.study.PgnDump,
    cacheApi: CacheApi,
    net: lila.common.config.NetConfig
)(using Executor):

  def getPgn(id: GameId) = if notGames.contains(id.value) then fuccess(none) else gamePgnCache get id
  def getChapterPgn(id: StudyChapterId) = chapterPgnCache get id
  def getStudyPgn(id: StudyId)          = studyPgnCache get id

  // forum linkRenderFromText builds a LinkRender from relative game|chapter urls -> lpv div tags.
  // substitution occurs in common/../RawHtml.scala addLinks
  def linkRenderFromText(text: String): Fu[lila.base.RawHtml.LinkRender] =
    regex.forumPgnCandidatesRe
      .findAllMatchIn(text)
      .map(_.group(1))
      .map:
        case regex.gamePgnRe(url, id)    => getPgn(GameId(id)).map(url -> _)
        case regex.chapterPgnRe(url, id) => getChapterPgn(StudyChapterId(id)).map(url -> _)
        case regex.studyPgnRe(url, id)   => getStudyPgn(StudyId(id)).map(url -> _)
        case link                        => fuccess(link -> link)
      .parallel
      .map:
        _.collect { case (url, Some(pgn)) => url -> pgn }.toMap
      .map: pgns =>
        (url, _) =>
          pgns
            .get(url)
            .map: pgn =>
              div(
                cls              := "lpv--autostart is2d",
                attr("data-pgn") := pgn.value,
                plyRe.findFirstIn(url).map(_.substring(1)).map(ply => attr("data-ply") := ply),
                (url contains "/black").option(attr("data-orientation") := "black")
              )

  // used by blogs & ublogs to build game|chapter id -> pgn maps
  // the substitution happens later in blog/BlogApi or common/MarkdownRender
  def allPgnsFromText(text: String): Fu[Map[String, PgnStr]] =
    regex.blogPgnCandidatesRe
      .findAllMatchIn(text)
      .map(_.group(1))
      .map:
        case regex.gamePgnRe(url, id)    => getPgn(GameId(id)).map(id -> _)
        case regex.chapterPgnRe(url, id) => getChapterPgn(StudyChapterId(id)).map(id -> _)
        case regex.studyPgnRe(url, id)   => getStudyPgn(StudyId(id)).map(id -> _)
        case link                        => fuccess(link -> link)
      .parallel
      .map:
        _.collect:
          case (id, Some(pgn)) => id -> pgn
        .toMap

  private val regex = LpvGameRegex(net.domain)
  private val plyRe = raw"#(\d+)\z".r

  private val notGames =
    Set("training", "analysis", "insights", "practice", "features", "password", "streamer", "timeline")

  private val pgnFlags =
    lila.game.PgnDump.WithFlags(clocks = true, evals = true, opening = false, literate = true)

  private val gamePgnCache = cacheApi[GameId, Option[PgnStr]](512, "textLpvExpand.pgn.game"):
    _.expireAfterWrite(10 minutes).buildAsyncFuture(id => gameIdToPgn(id).map2(_.render))

  private val chapterPgnCache = cacheApi[StudyChapterId, Option[PgnStr]](512, "textLpvExpand.pgn.chapter"):
    _.expireAfterWrite(10 minutes).buildAsyncFuture(studyChapterIdToPgn)

  private val studyPgnCache = cacheApi[StudyId, Option[PgnStr]](512, "textLpvExpand.pgn.firstChapter"):
    _.expireAfterWrite(10 minutes).buildAsyncFuture(studyIdToPgn)

  private def gameIdToPgn(id: GameId): Fu[Option[Pgn]] =
    gameRepo gameWithInitialFen id flatMapz { g =>
      analysisRepo.byId(id into Analysis.Id) flatMap { analysis =>
        pgnDump(g.game, g.fen, analysis, pgnFlags) dmap some
      }
    }

  private def studyChapterIdToPgn(id: StudyChapterId): Fu[Option[PgnStr]] =
    val flags = lila.study.PgnDump.fullFlags
    studyApi.byChapterId(id) flatMapz { sc =>
      studyPgnDump.ofChapter(sc.study, flags)(sc.chapter) dmap some
    }

  private def studyIdToPgn(id: StudyId): Fu[Option[PgnStr]] =
    val flags = lila.study.PgnDump.fullFlags
    studyApi.byId(id) flatMapz { s =>
      studyPgnDump.ofFirstChapter(s, flags)
    }

final class LpvGameRegex(domain: NetDomain):

  private val quotedDomain = java.util.regex.Pattern.quote(domain.value)

  val pgnCandidates = raw"""(?:https?://)?(?:lichess\.org|$quotedDomain)(/[/\w#]{8,})\b"""

  val blogPgnCandidatesRe  = pgnCandidates.r
  val forumPgnCandidatesRe = raw"(?m)^$pgnCandidates".r

  val params = raw"""(?:#(?:last|\d{1,4}))?"""

  val gamePgnRe    = raw"^(/(\w{8})(?:\w{4}|/(?:white|black))?$params)$$".r
  val chapterPgnRe = raw"^(/study/(?:embed/)?(?:\w{8})/(\w{8})$params)$$".r
  val studyPgnRe   = raw"^(/study/(?:embed/)?(\w{8})$params)$$".r
