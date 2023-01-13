// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License.AGPL.txt in the project root for license information.

package rollout

import (
	"context"
	"time"

	logrus "github.com/gitpod-io/gitpod/common-go/log"
	"github.com/gitpod-io/gitpod/workspace-rollout-job/pkg/analysis"
	"github.com/gitpod-io/gitpod/workspace-rollout-job/pkg/wsbridge"
)

type RollOutJob struct {
	oldCluster           string
	newCluster           string
	currentScore         int32
	analyzer             analysis.Analyzer
	RolloutAction        wsbridge.RolloutAction
	rolloutStep          int32
	analysisWaitDuration time.Duration

	ticker *time.Ticker
	revert chan bool
	done   chan bool
}

func New(oldCluster, newCluster string, rolloutWaitDuration, analysisWaitDuration time.Duration, step int32, analyzer analysis.Analyzer, rolloutAction wsbridge.RolloutAction) *RollOutJob {
	return &RollOutJob{
		oldCluster:           oldCluster,
		newCluster:           newCluster,
		currentScore:         0,
		rolloutStep:          step,
		analyzer:             analyzer,
		RolloutAction:        rolloutAction,
		done:                 make(chan bool, 1),
		revert:               make(chan bool, 1),
		analysisWaitDuration: analysisWaitDuration,
		// move forward every waitDuration
		ticker: time.NewTicker(rolloutWaitDuration),
	}
}

// Start runs the job synchronously
func (r *RollOutJob) Start(ctx context.Context) {
	// keep checking the analyzer asynchronously to see if there is a
	// problem with the new cluster
	log := logrus.WithField("component", "rollout-job")
	go func() {
		for {
			// Run only if the revert channel is empty
			if len(r.revert) == 0 {
				// check every analysisWaitDuration
				time.Sleep(r.analysisWaitDuration)
				moveForward, err := r.analyzer.MoveForward(context.Background(), r.newCluster)
				if err != nil {
					log.Error("Failed to retrieve new cluster error count: ", err)
					// Revert the rollout in case of analysis failure
					r.revert <- true
				}
				// Analyzer says no, stop the rollout
				if !moveForward {
					log.Info("Analyzer says no, stopping the rollout")
					r.revert <- true
				}
			}
		}
	}()

	for {
		select {
		case <-r.ticker.C:
			if r.currentScore == 100 {
				log.Info("Rollout completed")
				r.Stop()
				return
			}

			r.currentScore += r.rolloutStep
			// TODO (ask): Handle them together? so that we don't end up in a mixed state during failure
			if err := r.UpdateScoreWithMetricUpdate(ctx, r.newCluster, r.currentScore); err != nil {
				log.Error("Failed to update new cluster score: ", err)
			}

			if err := r.UpdateScoreWithMetricUpdate(ctx, r.oldCluster, 100-r.currentScore); err != nil {
				log.Error("Failed to update old cluster score: ", err)
			}

			log.Infof("Updated cluster scores: %s: %d, %s: %d", r.oldCluster, 100-r.currentScore, r.newCluster, r.currentScore)
		case <-r.revert:
			log.Info("Reverting the rollout")

			if err := r.UpdateScoreWithMetricUpdate(ctx, r.oldCluster, 100); err != nil {
				log.Error("Failed to update new cluster score: ", err)
			}

			if err := r.UpdateScoreWithMetricUpdate(ctx, r.newCluster, 0); err != nil {
				log.Error("Failed to update new cluster score: ", err)
			}

			log.Infof("Updated cluster scores: %s: %d, %s: %d", r.oldCluster, 100, r.newCluster, 0)
			r.Stop()

		case <-r.done:
			return
		}
	}
}

func (r *RollOutJob) UpdateScoreWithMetricUpdate(ctx context.Context, cluster string, score int32) error {
	if err := r.RolloutAction.UpdateScore(ctx, cluster, score); err != nil {
		scoreUpdatesFailuresTotal.WithLabelValues(cluster, err.Error()).Inc()
		return err
	}

	scoreUpdatesTotal.WithLabelValues(cluster).Inc()
	clusterScores.WithLabelValues(cluster).Set(float64(score))
	return nil
}

func (r *RollOutJob) Stop() {
	close(r.done)
	r.ticker.Stop()
}