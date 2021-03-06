package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.service.{ResponseClass, ReqRep, ResponseClassifier}
import com.twitter.finagle.util.LoadService
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.config.Parser
import io.buoyant.linkerd.{ResponseClassifierConfig, ResponseClassifierInitializer, RouterConfig}
import io.buoyant.linkerd.protocol.HttpInitializer
import org.scalatest.FunSuite

class ResponseClassifiersTest extends FunSuite {

  def testClassifier(
    classifier: ResponseClassifier,
    method: Method,
    status: Try[Status],
    classification: Option[ResponseClass]
  ): Unit = {
    val key = ReqRep(Request(method, "/"), status.map(Response(_)))
    classification match {
      case None =>
        assert(!classifier.isDefinedAt(key))
      case Some(classification) =>
        assert(classifier.isDefinedAt(key))
        assert(classifier(key) == classification)
    }
  }

  val allMethods = Set(
    Method.Connect,
    Method.Delete,
    Method.Get,
    Method.Head,
    Method.Patch,
    Method.Post,
    Method.Put,
    Method.Options,
    Method.Trace
  )

  for (
    (classifier, retryMethods) <- Map(
      ResponseClassifiers.RetryableIdempotentFailures ->
        Set(Method.Get, Method.Head, Method.Put, Method.Delete, Method.Options, Method.Trace),
      ResponseClassifiers.RetryableReadFailures ->
        Set(Method.Get, Method.Head, Method.Options, Method.Trace)
    )
  ) {

    for (method <- retryMethods) {
      test(s"$classifier: ignores $method 1XX-4XX") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: retries $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            Some(ResponseClass.RetryableFailure)
          )
      }

      test(s"$classifier: retries $method errors") {
        testClassifier(
          classifier,
          method,
          Throw(new Exception),
          Some(ResponseClass.RetryableFailure)
        )
      }
    }

    for (method <- allMethods -- retryMethods) {
      test(s"$classifier: ignores $method responses") {
        for (code <- 100 to 599)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: fails $method error") {
        testClassifier(classifier, method, Throw(new Exception), None)
      }
    }
  }

  {
    val classifier = ResponseClassifiers.NonRetryableServerFailures
    for (method <- allMethods) {
      test(s"$classifier: ignores $method 1XX-4XX") {
        for (code <- 100 to 499)
          testClassifier(classifier, method, Return(Status(code)), None)
      }

      test(s"$classifier: fails $method 5XX") {
        for (code <- 500 to 599)
          testClassifier(
            classifier,
            method,
            Return(Status(code)),
            Some(ResponseClass.NonRetryableFailure)
          )
      }
    }
  }

  for (
    init <- Seq(
      NonRetryable5XXInitializer,
      RetryableIdempotent5XXInitializer,
      RetryableRead5XXInitializer
    )
  ) {
    val kind = init.configId

    test(s"loads $kind") {
      assert(LoadService[ResponseClassifierInitializer]().exists(_.configId == kind))
    }

    test(s"parse router with $kind") {
      val yaml =
        s"""|protocol: http
            |responseClassifier:
            |  kind: $kind
            |servers:
            |- port: 0
            |""".stripMargin
      val mapper = Parser.objectMapper(yaml, Iterable(Seq(HttpInitializer), Seq(init)))
      assert(mapper.readValue[RouterConfig](yaml)._responseClassifier.isDefined)
    }
  }
}
