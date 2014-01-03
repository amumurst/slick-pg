package com.github.tminglei.slickpg
package utils

import scala.annotation.tailrec
import scala.util.parsing.combinator.RegexParsers
import scala.util.parsing.input.CharSequenceReader
import scala.slick.SlickException

class PGObjectTokenizer extends RegexParsers {

  // pg tokens, should be used internally only
  object PGTokens {
    sealed trait Token
    case object Comma                           extends Token

    case class ArrOpen(marker: String, l: Int)  extends Token
    case class RecOpen(marker: String, l: Int)  extends Token
    case class ArrClose(marker: String, l: Int) extends Token
    case class RecClose(marker: String, l: Int) extends Token
    case class Escape(escape: String, l:Int)    extends Token
    case class Marker(m : String, l: Int)       extends Token
    case object SingleQuote                     extends Token

    trait ValueToken extends Token {
      def value: String
    }
    case class Chunk(value: String)             extends ValueToken
    case class Quote(value: String)             extends ValueToken

    trait CompositeToken extends Token {
      def value: List[Token]
    }
    case class CTArray(value: List[Token])      extends CompositeToken
    case class CTRecord(value: List[Token])     extends CompositeToken
    case class CTString(value: List[Token])     extends CompositeToken
  }

  ////////////////////////////////////
  import PGTokens._
  import PGObjectTokenizer.PGElements._

  object PGTokenReducer {

    def compose(input : CompositeToken) : Element =  {

      @tailrec
      def mergeString(list : List[Token], tally: String = "") : String = {
        if(list.isEmpty) tally
        else
          list.head match {
            case Chunk(v) => mergeString(list.tail, tally + v)
            case Quote(v) => mergeString(list.tail, tally + v)
            case Escape(v, _) => mergeString(list.tail, tally + v)
            case Comma  => mergeString(list.tail, tally)
            case token => throw new IllegalArgumentException(s"unsupported token $token")
          }
      }

      // postgres should never return any ws between chunks and commas. for example : (1, ,2, )
      // This case class would handle that :
      // case Chunk(v) if v.trim.isEmpty => null
      //----------------------------------------------
      // This (1 ," lalal"   ,) on the other hand would be a separate matter.
      def mergeComposite(composite :CompositeToken, level: Int = 0) : Element = {
        val elements =
          composite.value.collect {
            case v: CTArray   => mergeComposite(v)
            case v: CTRecord  => mergeComposite(v)
            case CTString(v)  => ValueE(mergeString(v.slice(1,v.length-1)))
            case Chunk(v) => ValueE(v)
            case null => NullE
          }

        composite match {
          case CTArray(_) => ArrayE(elements)
          case CTRecord(_) => CompositeE(elements)
          case token => throw new IllegalArgumentException(s"unsupported token $token")
        }
      }

      //--
      mergeComposite(input)
    }

    def reduce(tokens : List[Token]): CompositeToken = {

      def isOpen(token: Token) = { token match {
        case ArrOpen(_,_) => true
        case RecOpen(_,_) => true
        case SingleQuote  => true
        case _ => false
      } }

      def nullCheck(lastToken: Token, targetList : List[Token]  ) : List[Token] = {
        lastToken match {
          case Comma => targetList :+ null
          case _ => targetList
        } }

      def innerClose(x : Token, xs: List[Token], depth: Int) =
        close(x, x, xs, x::List(), depth +1)

      @tailrec
      def close(borderToken: Token, lastToken: Token, source: List[Token], target : List[Token],
                depth: Int = 0, consumeCount: Int =1): (CompositeToken, Int) = {
        source match {
          case List() => throw new Exception("reduction should never hit recursive empty list base case.")
          case x :: xs =>
            x match {
              // CLOSING CASES
              case ArrClose(cm,cl) => borderToken match {
                case ArrOpen(sm,sl) if cm == sm && sl == cl => ( CTArray(nullCheck(lastToken,target) :+ x),consumeCount + 1)
                case _ => throw new Exception (s"open and close tags don't match : $borderToken - $x")
              }
              case RecClose(cm,cl) => borderToken match {
                case RecOpen(sm,sl) if cm == sm && sl == cl => (CTRecord(nullCheck(lastToken,target) :+ x),consumeCount + 1)
                case _ => throw new Exception (s"open and close tags don't match:  : $borderToken - $x")
              }
              case SingleQuote if borderToken == SingleQuote => (CTString(target :+ x ),consumeCount +1)
              // the else porting of this should be caught by the isOpen case below
              // OPENING CASES -> The results of these are siblings.
              case xx if isOpen(x) => {
                val (sibling, consumed) = innerClose(x,xs,depth+1)
                val new_source =  source.splitAt(consumed)._2
                close(borderToken,x,new_source,target :+ sibling,consumeCount = consumeCount + consumed)
              }
              case Comma => close(borderToken,x,xs,nullCheck(lastToken,target) :+ x, consumeCount = consumeCount + 1)
              case _ => close(borderToken, x, xs, target :+ x, consumeCount = consumeCount +1)
            }
        }
      }

      //--
      tokens match {
        case x :: xs if xs != List() =>
          val ret =
            x match {
              case xx if isOpen(x)  => close(x,x, xs, x :: List() )
              case _ => throw new Exception("open must always deal with an open token.")
            }
          if(ret._2 != tokens.size)
            throw new Exception("reduction step did not cover all tokens.")
          ret._1
      }
    }
  }

  object PGTokenReverser {
    val ESCAPE_LETTERS = """,|'|"|\\|\s|\(|\{""".r

    def reverse(elem: Element): String = {

      def escapeRequired(str: String): Boolean = ESCAPE_LETTERS findFirstIn str isDefined

      def addEscaped(buf: StringBuilder, ch: Char, level: Int, dual: Boolean): Unit =
        ch match {
          case '\''  => buf append "''"
          case '"'   => addMark(buf, level, dual)
          case '\\'  => for(i <- 1 to scala.math.pow(2,level).toInt) buf append "\\"
          case _  =>  buf append ch
        }

      def addMark(buf: StringBuilder, level: Int, dual: Boolean): Unit =
        level match {
          case l if l < 0 => // do nothing
          case 0    =>  buf append "\""
          case 1    =>  buf append (if (dual) "\"\"" else "\\\"")
          case 2    =>  buf append (if (dual) "\\\\\"\"" else "\\\"\\\"")
          case l if l > 2 => {
            for(i <- 1 to (scala.math.pow(2,level).toInt - 4))
              buf append "\\"
            buf append (if (dual) "\\\\\"\"" else "\\\"\\\"")
          }
        }

      def doReverse(buf: StringBuilder, elem: Element, level: Int, dual: Boolean): Unit =
        elem match {
          case ArrayE(vList) => {
            addMark(buf, level, dual)
            buf append "{"
            var first = true
            for(v <- vList) {
              if (first) first = false else buf append ","
              doReverse(buf, v, level + 1, dual)
            }
            buf append "}"
            addMark(buf, level, dual)
          }
          case CompositeE(eList) => {
            addMark(buf, level, dual)
            buf append "("
            var first = true
            for(e <- eList) {
              if (first) first = false else buf append ","
              doReverse(buf, e, level + 1, dual)
            }
            buf append ")"
            addMark(buf, level, dual)
          }
          case ValueE(v) => {
            if (escapeRequired(v)) {
              addMark(buf, level, dual)
              for(ch <- v) addEscaped(buf, ch, level + 1, dual)
              addMark(buf, level, dual)
            } else buf append v
          }
          case NullE  =>  // do nothing
        }

      //--
      val buf = new StringBuilder
      doReverse(buf, elem, -1, elem.isInstanceOf[CompositeE])
      buf.toString
    }
  }

  ////////////////////////////////////////////////////////////////////////////////
  import PGTokenReducer._

  override def skipWhitespace = false

  var level = -1

  def markerRe = "[\\\\|\"]+".r

  def open: Parser[Token] = opt(markerRe) ~ (elem('{') | elem('(')) ^^ { case(x ~ y) =>
    val marker_ = x.getOrElse("")
    val r = y match {
      case '{' => ArrOpen(marker_,level)
      case '(' => RecOpen(marker_,level)
    } ; level += 1; r }

  def close: Parser[Token] = (elem('}') | elem(')')) ~ opt(markerRe) ^^ { case (x~ y) =>
    level -= 1
    val marker_ = y.getOrElse("")
    x match {
      case '}' => ArrClose(marker_,level)
      case ')' => RecClose(marker_,level)
    } }

  def escape = """\\+[^"\\]""".r ^^ {x => Escape("\\" + x.last, level)}

  def marker =  markerRe ^^ { x=>
    val pow = scala.math.pow(2,level).toInt
    if(x.length % pow == 0) {
      x.length / pow match {
        case 1 => SingleQuote
        case _ => Quote("\"" * (x.length/pow))
      }
    }
    else {
      Marker(x,level)
    }
  }

  def comma = elem(',') ^^ { x=> Comma }
  def chunk = """[^}){(\\,"]+""".r ^^ { Chunk}
  def tokens = open | close | escape | marker | comma | chunk

  def tokenParser = rep(tokens)  ^^ { t=> compose(reduce(t)) }

  //--
  def tokenize(input : String) =
    parseAll(tokenParser, new CharSequenceReader(input)) match {
      case Success(result, _) => result
      case failure: NoSuccess => throw new SlickException(failure.msg)
    }

  def reverse(elem: Element) = PGTokenReverser.reverse(elem)
}

object PGObjectTokenizer {

  object PGElements {
    sealed trait Element
    case class ValueE(value: String) extends Element
    case object NullE extends Element
    case class ArrayE(elements: List[Element]) extends Element
    case class CompositeE(members: List[Element]) extends Element
  }

  def tokenize(input : String): PGElements.Element = {
    new PGObjectTokenizer().tokenize(input)
  }

  def reverse(elem: PGElements.Element): String = {
    new PGObjectTokenizer().reverse(elem)
  }
}
