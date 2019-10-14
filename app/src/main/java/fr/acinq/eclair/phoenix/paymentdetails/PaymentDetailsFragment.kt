/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.phoenix.paymentdetails

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.*
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.phoenix.BaseFragment
import fr.acinq.eclair.phoenix.R
import fr.acinq.eclair.phoenix.databinding.FragmentPaymentDetailsBinding
import fr.acinq.eclair.phoenix.utils.Transcriber
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.util.Either
import scala.util.Left
import scala.util.Right
import java.text.DateFormat
import java.util.*

class PaymentDetailsFragment : BaseFragment() {

  override val log: Logger = LoggerFactory.getLogger(this::class.java)

  private lateinit var mBinding: FragmentPaymentDetailsBinding
  // shared view model, living with payment details nested graph
  private val model: PaymentDetailsViewModel by navGraphViewModels(R.id.nav_graph_payment_details) //{ factory }
  private val args: PaymentDetailsFragmentArgs by navArgs()

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    mBinding = FragmentPaymentDetailsBinding.inflate(inflater, container, false)
    mBinding.lifecycleOwner = this
    return mBinding.root
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)

    model.payment.observe(viewLifecycleOwner, Observer {
      it?.let {
        context?.let { ctx ->
          when {
            // OUTGOING PAYMENT
            it.isLeft -> {
              val p = it.left().get()
              when (p.status()) {
                is OutgoingPaymentStatus.Failed -> {
                  mBinding.statusText.text = Html.fromHtml(ctx.getString(R.string.paymentdetails_status_sent_failed))
                  drawStatusIcon(R.drawable.ic_cross, R.color.red)
                }
                is OutgoingPaymentStatus.`Pending$` -> {
                  mBinding.statusText.text = Html.fromHtml(ctx.getString(R.string.paymentdetails_status_sent_pending))
                }
                is OutgoingPaymentStatus.Succeeded -> {
                  val status = p.status() as OutgoingPaymentStatus.Succeeded
                  mBinding.statusText.text = Html.fromHtml(ctx.getString(R.string.paymentdetails_status_sent_successful, Transcriber.relativeTime(ctx, status.completedAt())))
                  drawStatusIcon(if (args.fromEvent) R.drawable.ic_payment_success_animated else R.drawable.ic_payment_success_static, R.color.green)
                }
              }
              mBinding.amountValue.setAmount(p.amount())
            }
            // INCOMING PAYMENT
            it.isRight -> {
              val p = it.right().get()
              if (p.status() is IncomingPaymentStatus.Received) {
                val status = p.status() as IncomingPaymentStatus.Received
                mBinding.statusText.text = Html.fromHtml(ctx.getString(R.string.paymentdetails_status_received_successful, Transcriber.relativeTime(ctx, status.receivedAt())))
                mBinding.amountValue.setAmount(status.amount())
                drawStatusIcon(if (args.fromEvent) R.drawable.ic_payment_success_animated else R.drawable.ic_payment_success_static, R.color.green)
              } else {
                mBinding.statusText.text = Html.fromHtml(ctx.getString(R.string.paymentdetails_status_received_pending))
                mBinding.amountValue.setAmount(MilliSatoshi(0))
                drawStatusIcon(R.drawable.ic_clock, R.color.green)
              }
            }
          }
        }
      }
    })

    getPayment(args.direction == PaymentDirection.`OutgoingPaymentDirection$`.`MODULE$`.toString(), args.identifier)
    mBinding.model = model
  }

  override fun onStart() {
    super.onStart()
    mBinding.actionBar.setOnBackAction(View.OnClickListener { findNavController().popBackStack() })
    mBinding.showTechnicalsButton.setOnClickListener { findNavController().navigate(R.id.action_payment_details_to_payment_details_technicals) }
  }

  private fun animateBottomSection() {
    mBinding.bottomSection.apply {
      alpha = 0f
      visibility = View.VISIBLE
      translationY = 30f
      animate()
        .alpha(1f)
        .setStartDelay(300)
        .setInterpolator(DecelerateInterpolator())
        .translationY(0f)
        .setDuration(600)
        .setListener(null)
    }
  }

  private fun animateMidSection() {
    mBinding.midSection.apply {
      alpha = 0f
      visibility = View.VISIBLE
      translationY = 20f
      animate()
        .alpha(1f)
        .setStartDelay(100)
        .setInterpolator(DecelerateInterpolator())
        .translationY(0f)
        .setDuration(400)
        .setListener(null)
    }
  }

  private fun drawStatusIcon(drawableResId: Int, colorResId: Int) {
    val statusDrawable = resources.getDrawable(drawableResId, context?.theme)
    statusDrawable?.setTint(resources.getColor(colorResId))
    mBinding.statusImage.setImageDrawable(statusDrawable)
    if (statusDrawable is Animatable) {
      statusDrawable.start()
    }
    animateMidSection()
    animateBottomSection()
  }

  private fun getPayment(isSentPayment: Boolean, identifier: String) {
    lifecycleScope.launch(CoroutineExceptionHandler { _, exception ->
      log.error("error when retrieving payment from DB: ", exception)
      model.state.value = PaymentDetailsState.ERROR
    }) {
      model.state.value = PaymentDetailsState.RETRIEVING_PAYMENT_DATA
      if (isSentPayment) {
        val p = appKit.getSentPayment(UUID.fromString(identifier))
        if (p.isDefined) {
          model.payment.value = Left.apply(p.get())
        } else {
          model.payment.value = null
        }
      } else {
        val p = appKit.getReceivedPayment(ByteVector32.fromValidHex(identifier))
        if (p.isDefined) {
          model.payment.value = Right.apply(p.get())
        } else {
          model.payment.value = null
        }
      }
      model.state.value = PaymentDetailsState.DONE
    }
  }
}

enum class PaymentDetailsState {
  INIT, RETRIEVING_PAYMENT_DATA, DONE, ERROR
}

class PaymentDetailsViewModel : ViewModel() {
  private val log = LoggerFactory.getLogger(PaymentDetailsViewModel::class.java)

  val payment = MutableLiveData<Either<OutgoingPayment, IncomingPayment>>(null)
  val state = MutableLiveData(PaymentDetailsState.INIT)

  val description: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft && it.left().get().paymentRequest().isDefined -> it.left().get().paymentRequest().get().description().left().get()
        it.isRight -> it.right().get().paymentRequest().description().left().get()
        else -> ""
      }
    } ?: ""
  }

  val destination: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft -> it.left().get().targetNodeId().toString()
        else -> ""
      }
    } ?: ""
  }

  val paymentHash: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft -> it.left().get().paymentHash().toString()
        else -> it.right().get().paymentRequest().paymentHash().toString()
      }
    } ?: ""
  }

  val paymentRequest: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft && it.left().get().paymentRequest().isDefined -> PaymentRequest.write(it.left().get().paymentRequest().get())
        it.isRight -> PaymentRequest.write(it.right().get().paymentRequest())
        else -> ""
      }
    } ?: ""
  }

  val preimage: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isRight -> it.right().get().paymentPreimage().toString()
        else -> ""
      }
    } ?: ""
  }

  val createdAt: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft -> DateFormat.getDateTimeInstance().format(it.left().get().createdAt())
        it.isRight -> DateFormat.getDateTimeInstance().format(it.right().get().createdAt())
        else -> ""
      }
    } ?: ""
  }

  val completedAt: LiveData<String> = Transformations.map(payment) {
    it?.let {
      when {
        it.isLeft && it.left().get().status() is OutgoingPaymentStatus.Succeeded -> {
          val status = it.left().get().status() as OutgoingPaymentStatus.Succeeded
          DateFormat.getDateTimeInstance().format(status.completedAt())
        }
        it.isLeft && it.isLeft && it.left().get().status() is OutgoingPaymentStatus.Failed -> {
          val status = it.left().get().status() as OutgoingPaymentStatus.Failed
          DateFormat.getDateTimeInstance().format(status.completedAt())
        }
        it.isLeft -> DateFormat.getDateTimeInstance().format(it.left().get().createdAt())
        it.isRight && it.right().get().status() is IncomingPaymentStatus.Received -> {
          val status = it.left().get().status() as IncomingPaymentStatus.Received
          DateFormat.getDateTimeInstance().format(status.receivedAt())
        }
        else -> ""
      }
    } ?: ""
  }

  val expiredAt: LiveData<String> = Transformations.map(payment) {
    if (it != null && it.isRight && it.right().get().paymentRequest().expiry().isDefined) {
      DateFormat.getDateTimeInstance().format(it.right().get().paymentRequest().expiry().get())
    } else {
      ""
    }
  }

  val isSent: LiveData<Boolean> = Transformations.map(payment) { it?.isLeft ?: false }

}