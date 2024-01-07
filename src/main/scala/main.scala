package assignment

import scala.collection.mutable.HashMap
import scala.collection.immutable.HashMap
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.annotation.targetName
import scala.collection.{immutable, mutable}
import scala.concurrent.Await
import scala.concurrent.duration.Duration

case class Point(x: Int, y: Int)
case class Client(name: String, address: Point)
case class Product(name: String)

trait Add {
  type ReturnType
  @targetName("add")
  def +(product: Product, quantity: Int): ReturnType
}

trait Remove {
  type ReturnType
  @targetName("remove")
  def -(product: Product, quantity: Int): ReturnType
}

case class Purchase(collection: immutable.HashMap[Product, Int] = immutable.HashMap()) extends Add {
  override type ReturnType = Purchase

  @targetName("add")
  override def +(product: Product, quantity: Int): Purchase = {
    val newCollection = collection + (product -> (collection.getOrElse(product, 0) + quantity))
    Purchase(newCollection)
  }
}
case class Order(collection: immutable.HashMap[Product, Int] = immutable.HashMap()) extends Add with Remove {
  override type ReturnType = Order

  @targetName("add")
  override def +(product: Product, quantity: Int): Order = {
    val newCollection = collection + (product -> (collection.getOrElse(product, 0) + quantity))
    Order(newCollection)
  }

  @targetName("remove")
  override def -(product: Product, quantity: Int): Order = {
    val newQuantity = collection.getOrElse(product, 0) - quantity
    if (newQuantity <= 0) {
      Order(collection - product)
    } else {
      val newCollection = collection + (product -> newQuantity)
      Order(newCollection)
    }
  }
}
type Stock = immutable.HashMap[Product, Int]
type WishList = immutable.HashMap[Product, Int]
class StockHouse(address: Point, stock: Stock) extends Add with Remove {
  override type ReturnType = StockHouse

  @targetName("add")
  override def +(product: Product, quantity: Int): StockHouse = {
    val newStock = stock + (product -> (stock.getOrElse(product, 0) + quantity))
    StockHouse(address, newStock)
  }

  @targetName("remove")
  override def -(product: Product, quantity: Int): StockHouse = {
    val newStock = stock + (product -> (stock.getOrElse(product, 0) - quantity))
    StockHouse(address, newStock)
  }
  def distance(client: Client): Int = {
    val x = address.x - client.address.x
    val y = address.y - client.address.y
    Math.sqrt(x * x + y * y).toInt
  }

  def CheckAvailability(product: Product, amount: Int): Int  = {
    val productStock = stock.getOrElse(product, 0)
    if (productStock >= amount) {
      amount
    } else {
      productStock
    }
  }
}

trait Message
type ClientActor = ActorRef[Message]
type ProcessingActor = ActorRef[Message]
type StockHouseActor = ActorRef[Message]

case class PurchaseConfirmed(client: Client, clientActor: ClientActor) extends Message
case class AddProduct(product: Product, amount: Int, client: Client) extends Message
case class OrderConfirmed(client: Client, clientRef: ActorRef[Message]) extends Message
case class ConfirmPurchase(client: Client, clientRef : ActorRef[Message]) extends Message
case class OrderShipped() extends Message
case class OrderDelayed(reason: String) extends Message
case class FindStockHouses(client: Client) extends Message
case class CalculateDistance(client: Client, aggregator: ActorRef[Message]) extends Message
case class Distance(distance: Int, stockHouseActor: StockHouseActor) extends Message
case class FoundStockHouses(stockHouses: List[StockHouseActor], client: Client) extends Message
case class InStock(product: Product, amount: Int) extends Message
case class OutStock(product: Product) extends Message
case class FillOrder(product: Product, amount: Int, replyTo: ActorRef[Message]) extends Message


object StockHouseService {
  def apply(stockHouse: StockHouse): Behavior[Message] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage { message =>
        message match {
          case CalculateDistance(client, replyTo) =>
            val distance = stockHouse.distance(client)
            replyTo ! Distance(distance, context.self)
          case FillOrder(product, amount, replyTo) =>
            val productAmount = stockHouse.CheckAvailability(product, amount)
            if (productAmount > 0) {
              context.log.info(s"Stock house has $productAmount of $product")
              replyTo ! InStock(product, productAmount)
            } else {
              context.log.info(s"Stock house has no $product")
              replyTo ! OutStock(product)
            }
        }
        Behaviors.same
      }
    }
  }
}


object StockHousesAggregator {
  def apply(processingActor: ProcessingActor, NumberOfStockHouses: Int, client: Client): Behavior[Message] = {
    Behaviors.setup { context =>
      val stockHouses = mutable.HashMap[StockHouseActor, Int]()
      Behaviors.receiveMessage { message =>
        message match {
          case Distance(distance, stockHouseActor) =>
            context.log.info(s"$stockHouseActor is $distance away from client")
            stockHouses += (stockHouseActor -> distance)
            if (stockHouses.size == NumberOfStockHouses) {
              var closestStockHouses = List[StockHouseActor]()
              for i <- 1 to 5 do {
                val closest = stockHouses.minBy(_._2)._1
                stockHouses -= closest
                closestStockHouses = closestStockHouses :+ closest
                processingActor ! FoundStockHouses(closestStockHouses, client)
                Behaviors.stopped
              }
            }
        }
        Behaviors.same
      }
    }
  }
}

object OrderAggregator {
  private var order: Order = Order()
  private var responses = 0

  private def receiveResult(client: ClientActor): Behavior[Message] = Behaviors.receive((context, message) => {
    message match {
      case InStock(product, int) =>
        context.log.info(s"Order aggregator received $product from stock house")
        order = order - (product, int)
        responses += 1
        context.log.info(s"$responses")
        checkResult(client)
      case OutStock(product) =>
        context.log.info(s"Order aggregator received $product from stock house")
        responses += 1
        context.log.info(s"$responses")
        checkResult(client)
    }
  })

  def apply(client: ClientActor, goal: Order): Behavior[Message] = {
    order = goal
    receiveResult(client)
  }

  private def checkResult(client: ClientActor): Behavior[Message] = Behaviors.setup {
    context =>
      context.log.info(s"Order aggregator received $order from stock house")
      if (order.collection.isEmpty) {
        client ! OrderShipped()
        Behaviors.stopped
      } else if (responses == 5 * order.collection.size) {
        client ! OrderDelayed("Not enough stock")
        Behaviors.stopped
      } else {
        receiveResult(client)
      }
  }

}

object ProcessingService {
  def apply(stockHouseServiceActors: List[StockHouseActor]): Behavior[Message] = {
    Behaviors.setup { context =>
      val purchases = mutable.HashMap[Client, Purchase]()
      val clients = mutable.HashMap[Client, ClientActor]()
      val orders = mutable.HashMap[Client, Order]()
      Behaviors.receiveMessage { message =>
        message match {
          case AddProduct(product, amount, client) =>
            context.log.info(s"Processing service received request from client ${client.name} to add product ${product.name}")
            val purchase = purchases.getOrElse(client, Purchase())
            val newPurchase = purchase + (product, amount)
            purchases += (client -> newPurchase)
            context.log.info(s"Processing service added product ${product.name} to client ${client.name}'s purchase")
          case PurchaseConfirmed(client, clientActor) =>
            val purchase = purchases(client)
            context.log.info(s"Processing service received purchase $purchase from client ${client.name}")
            val order = Order(purchase.collection)
            clients += (client -> clientActor)
            orders += (client -> order)
            context.log.info(s"Processing service created order $order for client ${client.name}")
            val aggregator = context.spawnAnonymous(StockHousesAggregator(context.self, stockHouseServiceActors.size, client))
            stockHouseServiceActors.foreach(stockHouseServiceActor => stockHouseServiceActor ! CalculateDistance(client, aggregator))
          case FoundStockHouses(stockHouses, client) =>
            val aggregator = context.spawnAnonymous(OrderAggregator(clients(client), orders(client)))
            orders(client).collection.foreach((product, amount) => stockHouses.foreach(stockHouse => stockHouse ! FillOrder(product, amount, aggregator)))
        }
        Behaviors.same
      }
    }
  }
}

object ClientService {
  def apply(client: Client, processingServiceActor: ProcessingActor, toBuy: WishList): Behavior[Message] = {
    Behaviors.setup { context =>
      toBuy.foreach((product, amount) => processingServiceActor ! AddProduct(product, amount, client))
      processingServiceActor ! PurchaseConfirmed(client, context.self)
      Behaviors.receiveMessage { message =>
        message match {
          case OrderShipped() =>
            context.log.info(s"Order confirmed ${client.name}'s order has been placed.")
          case OrderDelayed(reason) =>
            context.log.info(s"Order denied ${client.name}'s order has been denied due to $reason.")
        }
        Behaviors.same
      }
    }
  }
}


object ShoppingSystem {
  def apply(): Behavior[Message] = {
    Behaviors.setup { context =>
      val client1 = Client("client1", Point(3, 5))

      val coal = Product("coal")
      val wood = Product("wood")
      val iron = Product("iron")
      val titanium = Product("titanium")
      val stone = Product("stone")
      val circuitBoard = Product("circuit board")
      val processor = Product("processor")
      val motor = Product("motor")

      val required = immutable.HashMap[Product, Int](
        coal -> 4,
        wood -> 2,
        iron -> 3,
        titanium -> 7,
        stone -> 5,
        circuitBoard -> 10,
        processor -> 8,
        motor -> 6
      )

      val stock1 = immutable.HashMap[Product, Int](
        coal -> 10,
        wood -> 10,
      )
      val stock2 = immutable.HashMap[Product, Int](
        iron -> 10,
        titanium -> 2,
        circuitBoard -> 7,
      )
      val stock3 = immutable.HashMap[Product, Int](
        stone -> 10,
        circuitBoard -> 10,
      )
      val stock4 = immutable.HashMap[Product, Int](
        processor -> 10,
        motor -> 10,
        titanium -> 10,
      )

      val stockHouses = List(
        StockHouse(Point(1, 7), stock1),
        StockHouse(Point(2, 4), stock2),
        StockHouse(Point(17, 9), stock3),
        StockHouse(Point(4, 4), stock4),
        StockHouse(Point(5, 5), stock3),
        StockHouse(Point(12, 6), stock2),
      )

      val stockHouseServices = stockHouses.map(stockHouse => context.spawnAnonymous(StockHouseService(stockHouse)))

      val processingService = context.spawn(ProcessingService(stockHouseServices), "processingService")
      val clientService = context.spawn(ClientService(client1, processingService, required), "clientService")

      context.system.terminate()
      Behaviors.same



    }
  }
}

object Assignment3 extends App {
  private val actorSystem = ActorSystem(ShoppingSystem(), "Assignment3")
  Await.ready(actorSystem.whenTerminated, Duration.Inf)
}
