package controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.actions.SecuredRequest
import com.mohiva.play.silhouette.api.{ LogoutEvent, Silhouette }
import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.mvc.{ AbstractController, AnyContent, ControllerComponents }
import utils.auth.DefaultEnv

import scala.concurrent.Future
import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.stream.Materializer
import chess.Chess
import com.mohiva.play.silhouette.impl.User
import model.Player
import models.daos.UserDAOImpl
import observer.Observer
import play.api.mvc._
import views.html._
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.libs.F.Tuple

import scala.collection.mutable.ListBuffer
import scala.swing.Reactor

/**
 * The basic application controller.
 *
 * @param components  The Play controller components.
 * @param silhouette  The Silhouette stack.
 * @param webJarsUtil The webjar util.
 * @param assets      The Play assets finder.
 */
class ApplicationController @Inject() (
  components: ControllerComponents,
  silhouette: Silhouette[DefaultEnv]
)(
  implicit
  webJarsUtil: WebJarsUtil,
  assets: AssetsFinder,
  system: ActorSystem,
  mat: Materializer
) extends AbstractController(components) with I18nSupport {

  var c: Chess = _
  var PLAYERA: String = ""
  var instance_counter = 0
  var currentPlayer: Tuple2[Int, Int] = _

  /**
   * Handles the index action.
   *
   * @return The result to display.
   */
  def index = silhouette.SecuredAction.async { implicit request: SecuredRequest[DefaultEnv, AnyContent] =>
    var users = new ListBuffer[String]()
    for (entry <- UserDAOImpl.users.valuesIterator) {
      users += entry.fullName.get
    }
    Future.successful(Ok(views.html.home(request.identity, users)))
  }

  /**
   * Handles the Sign Out action.
   *
   * @return The result to display.
   */
  def signOut = silhouette.SecuredAction.async { implicit request: SecuredRequest[DefaultEnv, AnyContent] =>
    val result = Redirect(routes.ApplicationController.index())
    silhouette.env.eventBus.publish(LogoutEvent(request.identity, request))
    silhouette.env.authenticatorService.discard(request.authenticator, result)
  }

  def startGame = silhouette.SecuredAction.async { implicit request: SecuredRequest[DefaultEnv, AnyContent] =>
    Future.successful(Ok(views.html.game(c.controller, request.identity)))
  }

  /**
   * Session Socket
   */

  def SessionSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef { out =>
      println("Session connection established")
      SessionSocketActorFactory.create(out)
    }
  }

  object SessionSocketActorFactory {
    def create(out: ActorRef) = {
      Props(new SessionSocketActor(out))
    }
  }

  class SessionSocketActor(out: ActorRef) extends Actor {
    def observe(): Unit = {
      while (true) {
        for (user <- UserDAOImpl.users.valuesIterator) {
          // TODO: player must be not be already play
          if (user.fullName.get != PLAYERA) {
            c = new Chess()
            c.controller.setPlayerA(new Player(PLAYERA))
            c.controller.setPlayerB(new Player(user.fullName.get))
            notifyClient()
            return
          }
        }
      }
    }
    def receive = {
      case msg: String =>
        out ! msg
        println("[Session] Receive Playername: " + msg)
        PLAYERA = msg
        observe()
    }

    def notifyClient() = {
      println("[Session] Notify Client")
      out ! "connected"
    }
  }

  /*
  * Chess Socket
  * */
  def socket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef { out =>
      println("Websocket Connect received")
      ChessWebSocketActorFactory.create(out)
    }
  }
  object ChessWebSocketActorFactory {
    def create(out: ActorRef) = {
      Props(new ChessWebSocketActor(out))
    }
  }

  class ChessWebSocketActor(out: ActorRef) extends Actor with Observer {

    c.controller.add(this)

    def receive = {
      case msg: String =>
        out ! c.controller.currentPlayer.toString()
        println("[Application] Receive: " + msg)
    }

    def notifyClient() = {
      println("[Application] Notify Client")
      out ! "" + currentPlayer + c.controller.target
    }
    def update() = notifyClient
  }

  /**
   * Move Figure and get Moves
   */
  def getMoves(x: String, y: String) = Action {
    var moves: ListBuffer[Tuple2[Int, Int]] = new ListBuffer[(Int, Int)]
    var filteredMoves: ListBuffer[Tuple2[Int, Int]] = new ListBuffer[(Int, Int)]
    if (c.controller.currentPlayer.hasFigure(c.gamefield.get((x.charAt(1).asDigit, y.charAt(1).asDigit)))) {
      moves = c.controller.getPossibleMoves((x.charAt(1).asDigit, y.charAt(1).asDigit))
      moves.foreach((t: Tuple2[Int, Int]) =>
        if (t._1 >= 0 && t._1 < 8 && t._2 >= 0 && t._2 < 8) {
          if (!c.controller.currentPlayer.hasFigure(c.gamefield.get(t))) {
            filteredMoves += t

          }
        }
      )
    }

    val jsonMoves = Json.obj(
      "moves" -> Json.toJson(filteredMoves.toList)
    )
    Ok(jsonMoves.toString())
  }
  def moveFigure(cpx: String, cpy: String, x: String, y: String) = Action {
    currentPlayer = (cpx.charAt(1).asDigit, cpy.charAt(1).asDigit)
    val target = (x.charAt(1).asDigit, y.charAt(1).asDigit)
    c.controller.putFigureTo(currentPlayer, target)
    Ok("Ok")
  }
}